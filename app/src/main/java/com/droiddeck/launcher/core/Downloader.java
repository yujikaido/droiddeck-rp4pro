package com.droiddeck.launcher.core;

import android.util.Log;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.SocketTimeoutException;
import java.net.URL;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Random;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Plain HTTPS GETs with retries. A failed try is repeated with exponential backoff and jitter; a
 * file in a GitHub repository (raw.githubusercontent.com) is also tried through github.com/raw and
 * the jsDelivr GitHub CDN, which helps on networks where one GitHub host drops connections.
 *
 * Mirrors are only used for text (catalogs, which come from the same repository either way) and for
 * files whose sha256 the caller passes: such a file is checked before success is reported, so a
 * mirror that serves anything else is skipped like a failed try. Release assets have no mirror and
 * are only retried. After the last try, {@link #lastFailure()} says what went wrong on this thread.
 */
public final class Downloader {
    private static final String TAG = "Downloader";
    static final int CONNECT_TIMEOUT_MS = 15_000;
    static final int READ_TIMEOUT_MS = 30_000;

    /** How often and how patiently a request is repeated. Tests swap in a fast one. */
    public static final class Policy {
        final int rounds;
        final int maxTries;
        final long baseDelayMs;
        final long maxDelayMs;
        final int connectTimeoutMs;
        final int readTimeoutMs;

        public Policy(int rounds, int maxTries, long baseDelayMs, long maxDelayMs, int connectTimeoutMs, int readTimeoutMs) {
            this.rounds = Math.max(1, rounds);
            this.maxTries = Math.max(1, maxTries);
            this.baseDelayMs = Math.max(0, baseDelayMs);
            this.maxDelayMs = Math.max(this.baseDelayMs, maxDelayMs);
            this.connectTimeoutMs = connectTimeoutMs;
            this.readTimeoutMs = readTimeoutMs;
        }
    }

    /** Up to 4 rounds over the hosts (at most 9 tries), waiting about 1, 2, then 4 s between rounds. */
    public static final Policy DEFAULT_POLICY = new Policy(4, 9, 1_000, 8_000, CONNECT_TIMEOUT_MS, READ_TIMEOUT_MS);

    interface Sleeper { void sleep(long ms) throws InterruptedException; }

    static volatile Policy policy = DEFAULT_POLICY;
    static volatile Sleeper sleeper = Thread::sleep;
    static volatile boolean mirrorsEnabled = true;
    private static final Random RANDOM = new Random();

    /** What the last failed call on a thread ran into, or null after a success. */
    public static final class Failure {
        public final int tries;
        public final List<String> hosts;
        public final String last;

        Failure(int tries, List<String> hosts, String last) {
            this.tries = tries;
            this.hosts = hosts;
            this.last = last;
        }

        @Override public String toString() {
            return tries + " tries via " + String.join(", ", hosts) + ": " + last;
        }
    }

    private static final ThreadLocal<Failure> LAST_FAILURE = new ThreadLocal<>();

    public static Failure lastFailure() { return LAST_FAILURE.get(); }

    public static void forgetFailure() { LAST_FAILURE.remove(); }

    private Downloader() {}

    // ---- URL candidates ------------------------------------------------------------------

    private static final String PART = "[A-Za-z0-9._-]+";
    // the three hosts of a repository file; tests point them at a local server
    static volatile String rawBase = "https://raw.githubusercontent.com";
    static volatile String jsdelivrBase = "https://cdn.jsdelivr.net/gh";
    static volatile String githubBase = "https://github.com";

    /**
     * The URL itself first, then the same repository file on the other hosts: raw.githubusercontent.com,
     * the jsDelivr GitHub CDN, github.com/raw (which redirects to raw, so it comes last). Anything that
     * is not a plain repository file (release assets, the API, URLs with a query) has no mirror.
     */
    public static List<String> candidates(String url) {
        List<String> out = new ArrayList<>();
        out.add(url);
        if (url == null || url.indexOf('?') >= 0 || url.indexOf('#') >= 0) return out;
        String raw = rawBase, jsd = jsdelivrBase, gh = githubBase;
        String tail = "(" + PART + ")/(" + PART + ")";
        Matcher m = Pattern.compile("^" + Pattern.quote(raw) + "/" + tail + "/(" + PART + ")/(.+)$").matcher(url);
        if (!m.matches()) m = Pattern.compile("^" + Pattern.quote(gh) + "/" + tail + "/raw/(" + PART + ")/(.+)$").matcher(url);
        if (!m.matches()) m = Pattern.compile("^" + Pattern.quote(jsd) + "/" + tail + "@(" + PART + ")/(.+)$").matcher(url);
        if (!m.matches()) return out;
        String owner = m.group(1), repo = m.group(2), ref = m.group(3), path = m.group(4);
        if (path.contains("..") || path.startsWith("/") || path.contains("//")) return out;
        LinkedHashSet<String> all = new LinkedHashSet<>(out);
        all.add(raw + "/" + owner + "/" + repo + "/" + ref + "/" + path);
        all.add(jsd + "/" + owner + "/" + repo + "@" + ref + "/" + path);
        all.add(gh + "/" + owner + "/" + repo + "/raw/" + ref + "/" + path);
        return new ArrayList<>(all);
    }

    /** Full-jitter backoff before round {@code round} (1-based): a random wait in [d/2, d], d = base * 2^(round-1), capped. */
    static long backoffMs(Policy p, int round, Random random) {
        if (round <= 0 || p.baseDelayMs == 0) return 0;
        long d = p.baseDelayMs << Math.min(round - 1, 20);
        if (d <= 0 || d > p.maxDelayMs) d = p.maxDelayMs;
        long half = d / 2;
        return half + (long) (random.nextDouble() * (d - half + 1));
    }

    private static String host(String url) {
        try { return new URL(url).getHost(); } catch (Exception e) { return url; }
    }

    // ---- one try -------------------------------------------------------------------------

    private enum Kind { OK, RETRY, GIVE_UP }

    private static final class Outcome {
        final Kind kind;
        final String why;
        final String body;
        Outcome(Kind kind, String why, String body) { this.kind = kind; this.why = why; this.body = body; }
        static Outcome ok(String body) { return new Outcome(Kind.OK, null, body); }
        static Outcome retry(String why) { return new Outcome(Kind.RETRY, why, null); }
        static Outcome giveUp(String why) { return new Outcome(Kind.GIVE_UP, why, null); }
    }

    /** 408, 425, 429 and 5xx may pass; any other answer stays the same on that host. */
    static boolean retryable(int code) {
        return code == 408 || code == 425 || code == 429 || code >= 500;
    }

    private static Outcome httpError(int code) {
        return retryable(code) ? Outcome.retry("HTTP " + code) : Outcome.giveUp("HTTP " + code);
    }

    private static String describe(Exception e) {
        if (e instanceof SocketTimeoutException) return "timed out";
        if (e instanceof UnknownHostException) return "host not found";
        String m = e.getMessage();
        return e.getClass().getSimpleName() + (m == null || m.isEmpty() ? "" : " (" + m + ")");
    }

    private interface Attempt { Outcome run(String url, int tryNo); }

    /**
     * Runs {@code attempt} over the candidates, round by round, until one succeeds, every host has
     * given a final answer, or the tries run out. Hosts within a round are tried back to back (a
     * different host needs no wait); rounds are separated by the backoff.
     */
    private static Outcome withRetries(String what, List<String> urls, Attempt attempt) {
        Policy p = policy;
        boolean[] done = new boolean[urls.size()];
        LinkedHashSet<String> hosts = new LinkedHashSet<>();
        String last = "no try made";
        int tries = 0;
        outer:
        for (int round = 0; round < p.rounds; round++) {
            if (round > 0) {
                boolean anyLeft = false;
                for (boolean d : done) anyLeft |= !d;
                if (!anyLeft || tries >= p.maxTries) break;
                try {
                    sleeper.sleep(backoffMs(p, round, RANDOM));
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    last = "interrupted";
                    break;
                }
            }
            for (int i = 0; i < urls.size(); i++) {
                if (done[i]) continue;
                if (tries >= p.maxTries) break outer;
                String url = urls.get(i);
                hosts.add(host(url));
                tries++;
                Outcome o = attempt.run(url, tries);
                if (o.kind == Kind.OK) {
                    LAST_FAILURE.remove();
                    if (tries > 1) Log.i(TAG, what + " " + url + ": ok on try " + tries);
                    return o;
                }
                last = host(url) + ": " + o.why;
                Log.w(TAG, what + " " + url + " (try " + tries + "): " + o.why);
                if (o.kind == Kind.GIVE_UP) done[i] = true;
                if (Thread.currentThread().isInterrupted()) break outer;
            }
        }
        LAST_FAILURE.set(new Failure(tries, new ArrayList<>(hosts), last));
        Log.w(TAG, what + " " + urls.get(0) + " failed: " + LAST_FAILURE.get());
        return Outcome.giveUp(last);
    }

    // ---- text ----------------------------------------------------------------------------

    /** The body as UTF-8, or null. Repository files are also tried on the mirrors. */
    public static String downloadString(String url) {
        List<String> urls = mirrorsEnabled ? candidates(url) : java.util.Collections.singletonList(url);
        return withRetries("GET", urls, (u, n) -> getString(u)).body;
    }

    private static Outcome getString(String url) {
        HttpURLConnection connection = null;
        try {
            connection = open(url);
            int code = connection.getResponseCode();
            if (code / 100 != 2) return httpError(code);
            try (InputStream in = connection.getInputStream();
                 ByteArrayOutputStream out = new ByteArrayOutputStream()) {
                FileUtils.copy(in, out);
                long length = connection.getContentLengthLong();
                if (length >= 0 && out.size() < length) return Outcome.retry("cut off after " + out.size() + " of " + length + " bytes");
                return Outcome.ok(new String(out.toByteArray(), StandardCharsets.UTF_8));
            }
        } catch (Exception e) {
            return Outcome.retry(describe(e));
        } finally {
            if (connection != null) connection.disconnect();
        }
    }

    // ---- files ---------------------------------------------------------------------------

    public static boolean downloadFile(String url, File destination, boolean resume,
                                       java.util.function.Consumer<Float> progress) {
        return downloadFile(url, destination, resume, progress, null);
    }

    /**
     * Downloads {@code url} into {@code destination}. With {@code resume}, bytes already in the file
     * are kept and the rest is asked for with a Range header; bytes kept from an earlier try of this
     * call are resumed only from the same URL. With {@code sha256}, a repository file may also come
     * from a mirror, and the file is checked against it before true is returned. The caller still
     * checks its own hash; this one only decides whether to try the next host.
     */
    public static boolean downloadFile(String url, File destination, boolean resume,
                                       java.util.function.Consumer<Float> progress, String sha256) {
        boolean verify = sha256 != null && !sha256.isEmpty();
        List<String> urls = verify && mirrorsEnabled ? candidates(url) : java.util.Collections.singletonList(url);
        boolean checkHere = verify && urls.size() > 1;
        // which URL the bytes now in the file came from; only that URL may be asked for the rest
        final String[] keptFrom = {resume ? url : null};
        boolean ok = withRetries("download", urls, (u, n) -> {
            boolean resumeThis = u.equals(keptFrom[0]) && destination.isFile() && destination.length() > 0;
            Outcome o = getFile(u, destination, resumeThis, progress);
            if (o.kind == Kind.RETRY && destination.isFile() && destination.length() > 0) {
                keptFrom[0] = u;
            } else if (o.kind != Kind.OK) {
                destination.delete();
                keptFrom[0] = null;
            } else if (checkHere) {
                String actual;
                try { actual = Hashes.INSTANCE.sha256(destination); } catch (Exception e) { actual = ""; }
                if (!sha256.equalsIgnoreCase(actual)) {
                    destination.delete();
                    keptFrom[0] = null;
                    return Outcome.giveUp("checksum mismatch");
                }
            }
            return o;
        }).kind == Kind.OK;
        // bytes from a mirror are not left for a later call that resumes from the first URL
        if (!ok && keptFrom[0] != null && !keptFrom[0].equals(url)) destination.delete();
        return ok;
    }

    private static Outcome getFile(String url, File destination, boolean resume,
                                   java.util.function.Consumer<Float> progress) {
        HttpURLConnection connection = null;
        long have = resume && destination.isFile() ? destination.length() : 0;
        if (!resume) destination.delete();
        try {
            connection = open(url);
            if (have > 0) connection.setRequestProperty("Range", "bytes=" + have + "-");
            int code = connection.getResponseCode();
            boolean appending = code == HttpURLConnection.HTTP_PARTIAL;
            if (appending && !rangeStartsAt(connection.getHeaderField("Content-Range"), have)) {
                // A 206 that does not begin where the kept bytes end would corrupt the file if appended.
                connection.disconnect();
                connection = null;
                destination.delete();
                return getFile(url, destination, false, progress);
            }
            if (code / 100 != 2) {
                if (have > 0) {
                    // the kept bytes do not fit what the server has now (416 and the like): start over
                    connection.disconnect();
                    connection = null;
                    destination.delete();
                    return getFile(url, destination, false, progress);
                }
                return httpError(code);
            }
            if (!appending) have = 0;
            long length = connection.getContentLengthLong();
            long total = length < 0 ? -1 : length + have;
            File parent = destination.getParentFile();
            if (parent != null && !parent.isDirectory())
                parent.mkdirs();
            long written = have;
            try (InputStream in = new BufferedInputStream(connection.getInputStream(), 1 << 16);
                 OutputStream out = new FileOutputStream(destination, appending)) {
                byte[] buffer = new byte[1 << 16];
                long lastReport = 0;
                for (int read = in.read(buffer); read > 0; read = in.read(buffer)) {
                    out.write(buffer, 0, read);
                    written += read;
                    if (progress != null && written - lastReport > (1 << 20)) {
                        lastReport = written;
                        progress.accept(total > 0 ? written / (float) total : -1f);
                    }
                }
            } catch (Exception e) {
                return Outcome.retry(describe(e) + " after " + written + " bytes");
            }
            if (total > 0 && written < total) return Outcome.retry("cut off after " + written + " of " + total + " bytes");
            if (progress != null) progress.accept(1f);
            return Outcome.ok(null);
        } catch (Exception e) {
            return Outcome.retry(describe(e));
        } finally {
            if (connection != null) connection.disconnect();
        }
    }

    /** True when a `Content-Range: bytes START-END/TOTAL` header starts exactly at {@code start}. */
    static boolean rangeStartsAt(String contentRange, long start) {
        if (contentRange == null) return false;
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("^\\s*bytes\\s+(\\d+)-\\d+/(?:\\d+|\\*)\\s*$").matcher(contentRange);
        return m.matches() && Long.parseLong(m.group(1)) == start;
    }

    private static HttpURLConnection open(String url) throws java.io.IOException {
        Policy p = policy;
        HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
        connection.setConnectTimeout(p.connectTimeoutMs);
        connection.setReadTimeout(p.readTimeoutMs);
        connection.setInstanceFollowRedirects(true);
        connection.setRequestProperty("User-Agent", "DroidDeck-Android");
        return connection;
    }
}
