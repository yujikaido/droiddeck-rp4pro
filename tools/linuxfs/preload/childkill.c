/* Steam's browser children: who ends them.
 *
 * On the POCO X8 Pro class (Xiaomi 2511FPC34G, MT6899, HyperOS 3) and on vivo V2515 (MT6993) Steam's
 * menu never shows, or shows at a few fps, because the renderer of its SharedJSContext ends 0.3-4 s
 * after every start: cef_log.txt "Tab Killed: https://steamloopback.host/" about once a second, a new
 * `steamwebhelper --type=renderer` each time (a player log, gon070's a player log/4ZFQ). "Killed" is
 * Chromium's word for a child that its zygote's waitpid saw end by SIGKILL/SIGTERM/SIGINT, which is
 * also what Chromium's own reap after a broken channel looks like (it sends SIGKILL itself), so the
 * logs cannot say who ended it. Here, with BL_VKBRIDGE=1 (BL_WEBHELPER_EXIT_DIAG=0 off, =1 on anywhere),
 * in steamwebhelper processes only (release prep T11, owner 11-04: every other guest process - Steam,
 * gamescope, Wine, FEX, the games - calls the real kill/wait directly, nothing read or written; a
 * crash handler's kill there gains no work that could hang it). BL_WEBHELPER_EXIT_DIAG=all also hooks the
 * kills of every guest process, as the first B4 build did, to name a guest sender (B4 test builds):
 *   - a kill/killpg/tgkill/sigqueue with a signal that ends or stops a
 *     process, aimed at a steamwebhelper process (or its process group), is written down with who sent
 *     it, the target's type, its state at that moment (Z = it had already ended) and its oom_score_adj;
 *   - in steamwebhelper processes (the browser and its zygotes): a child that waitpid/wait4 reports
 *     ended by a signal or with a non-zero status is written down with its oom_score_adj, the free
 *     memory and a verdict from the lines above: a sender in the guest, Chromium itself while the child
 *     still ran, or nobody in the guest (Android or the kernel);
 *   - a steamwebhelper child that calls exit/_exit/_Exit with a non-zero status (gpuexit.c's hooks
 *     call bl_webhelper_exit_note) says so with the caller.
 * Lines go to Steam's log folder (droiddeck_webhelper_exits.txt, collected with the other Steam logs,
 * started afresh past 256 KB); steamwebhelper processes also write them to stderr. Every call is passed
 * on unchanged (arguments, result, errno); nothing is written for signal 0 or other signals. The paths
 * that may run in a vfork child use no malloc, no stdio and no cached descriptors. */
#define _GNU_SOURCE
#include <dlfcn.h>
#include <errno.h>
#include <fcntl.h>
#include <signal.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/resource.h>
#include <sys/stat.h>
#include <sys/syscall.h>
#include <sys/types.h>
#include <sys/wait.h>
#include <time.h>
#include <unistd.h>

#define EXIT_DIAG_MAX_BYTES (256 * 1024)
#define EXIT_DIAG_TAIL (32 * 1024)
#define NO_ADJ (-10000)

static int exit_diag_on;        /* the kill hooks note: in steamwebhelper, or every process with =all */
static int exit_diag_webhelper; /* this process is steamwebhelper: zygote forks inherit it */
static int exit_diag_all;       /* BL_WEBHELPER_EXIT_DIAG=all: senders outside the webhelper are seen too */
static long exit_diag_gmtoff;   /* local time offset, from the constructor (localtime is not safe in the hooks) */

static int (*real_kill)(pid_t, int);
static int (*real_killpg)(pid_t, int);
static int (*real_tgkill)(pid_t, pid_t, int);
static int (*real_sigqueue)(pid_t, int, const union sigval);
static pid_t (*real_waitpid)(pid_t, int *, int);
static pid_t (*real_wait4)(pid_t, int *, int, struct rusage *);
static int (*next_openat)(int, const char *, int, ...);
static int (*next_close)(int);

/* Signals that end or stop a process. Exposed (hidden) for the host test. */
__attribute__((visibility("hidden"))) int bl_childkill_watched(int sig) {
  switch (sig) {
    case SIGKILL: case SIGTERM: case SIGINT: case SIGQUIT: case SIGABRT: case SIGHUP:
    case SIGSEGV: case SIGBUS: case SIGILL: case SIGFPE: case SIGSYS: case SIGSTOP:
      return 1;
    default:
      return 0;
  }
}

static const char *sig_name(int sig) {
  switch (sig) {
    case SIGKILL: return "SIGKILL";
    case SIGTERM: return "SIGTERM";
    case SIGINT: return "SIGINT";
    case SIGQUIT: return "SIGQUIT";
    case SIGABRT: return "SIGABRT";
    case SIGHUP: return "SIGHUP";
    case SIGSEGV: return "SIGSEGV";
    case SIGBUS: return "SIGBUS";
    case SIGILL: return "SIGILL";
    case SIGFPE: return "SIGFPE";
    case SIGSYS: return "SIGSYS";
    case SIGSTOP: return "SIGSTOP";
    case SIGTRAP: return "SIGTRAP";
    default: return "signal";
  }
}

static int raw_open(const char *path, int flags, int mode) {
  if (next_openat) return next_openat(AT_FDCWD, path, flags, mode);
  return openat(AT_FDCWD, path, flags, mode);
}

static void raw_close(int fd) {
  if (next_close) next_close(fd);
  else close(fd);
}

static ssize_t read_small(const char *path, char *buf, size_t size) {
  int fd = raw_open(path, O_RDONLY | O_CLOEXEC, 0);
  if (fd < 0) return -1;
  ssize_t n = read(fd, buf, size - 1);
  raw_close(fd);
  if (n < 0) return -1;
  buf[n] = 0;
  return n;
}

/* comm and state from /proc/<pid>/stat ("pid (comm) S ..."); both still there for a zombie */
static int proc_comm_state(pid_t pid, char *comm, size_t csize, char *state) {
  char path[48], buf[512];
  snprintf(path, sizeof(path), "/proc/%d/stat", (int)pid);
  if (read_small(path, buf, sizeof(buf)) <= 0) return -1;
  char *open_paren = strchr(buf, '('), *close_paren = strrchr(buf, ')');
  if (!open_paren || !close_paren || close_paren < open_paren) return -1;
  size_t n = (size_t)(close_paren - open_paren - 1);
  if (n >= csize) n = csize - 1;
  memcpy(comm, open_paren + 1, n);
  comm[n] = 0;
  *state = close_paren[1] == ' ' && close_paren[2] ? close_paren[2] : '?';
  return 0;
}

static int is_webhelper_pid(pid_t pid, char *state) {
  char comm[32];
  return pid > 0 && !proc_comm_state(pid, comm, sizeof(comm), state) && !strcmp(comm, "steamwebhelper");
}

/* The role of a steamwebhelper process from its NUL-separated command line: "renderer",
 * "gpu-process", "utility network.mojom.NetworkService", "zygote", "browser"; "ended" for a zombie (its
 * command line is gone). Exposed (hidden) for the host test. */
__attribute__((visibility("hidden"))) void bl_childkill_role(const char *cmdline, size_t len, char *out, size_t size) {
  const char *type = NULL, *sub = NULL;
  if (!cmdline || !len) {
    snprintf(out, size, "ended");
    return;
  }
  for (size_t i = 0; i < len;) {
    const char *arg = cmdline + i;
    if (!strncmp(arg, "--type=", 7)) type = arg + 7;
    else if (!strncmp(arg, "--utility-sub-type=", 19)) sub = arg + 19;
    i += strnlen(arg, len - i) + 1;
  }
  if (!type) snprintf(out, size, "browser");
  else if (sub) snprintf(out, size, "%s %s", type, sub);
  else snprintf(out, size, "%s", type);
}

static void pid_role(pid_t pid, char *out, size_t size) {
  char path[48], buf[2048];
  snprintf(path, sizeof(path), "/proc/%d/cmdline", (int)pid);
  ssize_t n = read_small(path, buf, sizeof(buf));
  bl_childkill_role(buf, n > 0 ? (size_t)n : 0, out, size);
}

static int pid_adj(pid_t pid) {
  char path[48], buf[24];
  snprintf(path, sizeof(path), "/proc/%d/oom_score_adj", (int)pid);
  if (read_small(path, buf, sizeof(buf)) <= 0) return NO_ADJ;
  return atoi(buf);
}

static void adj_text(int adj, char *out, size_t size) {
  if (adj == NO_ADJ) snprintf(out, size, "?");
  else snprintf(out, size, "%d", adj);
}

/* Who this process is: "steamwebhelper zygote" or the first two words of its command line */
static void self_desc(char *out, size_t size) {
  char buf[512];
  ssize_t n = read_small("/proc/self/cmdline", buf, sizeof(buf));
  if (n <= 0) {
    snprintf(out, size, "?");
    return;
  }
  if (exit_diag_webhelper) {
    char role[96];
    bl_childkill_role(buf, (size_t)n, role, sizeof(role));
    snprintf(out, size, "steamwebhelper %.90s", role);
    return;
  }
  const char *argv0 = buf, *base = strrchr(argv0, '/');
  base = base ? base + 1 : argv0;
  size_t first = strlen(argv0) + 1;
  const char *argv1 = first < (size_t)n ? buf + first : "";
  const char *base1 = strrchr(argv1, '/');
  base1 = base1 && argv1[0] == '/' ? base1 + 1 : argv1;
  snprintf(out, size, "%.40s%s%.60s", base, *base1 ? " " : "", base1);
}

static long mem_available_mb(void) {
  char buf[2048];
  if (read_small("/proc/meminfo", buf, sizeof(buf)) <= 0) return -1;
  char *p = strstr(buf, "MemAvailable:");
  return p ? atol(p + 13) / 1024 : -1;
}

static int stamp(char *out, size_t size) {
  struct timespec t;
  clock_gettime(CLOCK_REALTIME, &t);
  time_t local = t.tv_sec + exit_diag_gmtoff;
  struct tm tm;
  gmtime_r(&local, &tm);
  return snprintf(out, size, "%04d-%02d-%02d %02d:%02d:%02d.%03ld ", tm.tm_year + 1900, tm.tm_mon + 1, tm.tm_mday,
                  tm.tm_hour, tm.tm_min, tm.tm_sec, t.tv_nsec / 1000000);
}

static void log_path(char *path, size_t size) {
  const char *data = getenv("XDG_DATA_HOME");
  const char *home = getenv("HOME");
  if (data && *data) snprintf(path, size, "%s/Steam/logs/droiddeck_webhelper_exits.txt", data);
  else snprintf(path, size, "%s/.local/share/Steam/logs/droiddeck_webhelper_exits.txt", home && *home ? home : "/root");
}

static void log_line(const char *line, size_t n) {
  char path[512];
  log_path(path, sizeof(path));
  struct stat st;
  int flags = O_WRONLY | O_CREAT | O_APPEND | O_CLOEXEC;
  if (!stat(path, &st) && st.st_size > EXIT_DIAG_MAX_BYTES) flags |= O_TRUNC;
  int fd = raw_open(path, flags, 0644);
  if (fd >= 0) {
    if (write(fd, line, n) < 0) { /* nothing more to do */ }
    raw_close(fd);
  }
  if (exit_diag_webhelper && write(2, line, n) < 0) { /* nothing more to do */ }
}

static void note_signal(const char *how, pid_t pid, int sig) {
  pid_t target = pid;
  const char *what = "pid";
  if (pid == -1) {
    target = -1;
  } else if (pid <= 0) {
    target = pid == 0 ? getpgrp() : -pid;
    what = "process group";
  }
  char state = '?';
  if (target != -1 && !is_webhelper_pid(target, &state)) return;
  char line[640], me[128], role[96], adj[16];
  int m = stamp(line, sizeof(line));
  self_desc(me, sizeof(me));
  if (target == -1) {
    m += snprintf(line + m, sizeof(line) - m, "droiddeck: %s (pid %d) sends %s (%d) to every process it may signal, via %s\n",
                  me, (int)getpid(), sig_name(sig), sig, how);
  } else {
    pid_role(target, role, sizeof(role));
    adj_text(pid_adj(target), adj, sizeof(adj));
    m += snprintf(line + m, sizeof(line) - m,
                  "droiddeck: %s (pid %d) sends %s (%d) to steamwebhelper %s %d (%s), state %c%s, oom_score_adj %s, via %s\n",
                  me, (int)getpid(), sig_name(sig), sig, what, (int)target, role, state,
                  state == 'Z' ? " (it had already ended)" : "", adj, how);
  }
  if (m > 0) log_line(line, (size_t)m < sizeof(line) ? (size_t)m : sizeof(line) - 1);
}

/* One sender line: who (text before "(pid N) sends"), its pid, and the target's state then */
struct sender {
  const char *from;
  size_t who;
  int pid;
  char state;
};

static int parse_sender(const char *line, const char *end, struct sender *s) {
  const char *from = memmem(line, (size_t)(end - line), "droiddeck: ", 11);
  if (!from) return 0;
  from += 11;
  const char *sends = memmem(from, (size_t)(end - from), ") sends ", 8);
  if (!sends) return 0;
  const char *pid_at = NULL;
  for (const char *q = from; q + 5 <= sends; q++)
    if (!memcmp(q, "(pid ", 5)) pid_at = q;
  if (!pid_at) return 0;
  const char *st = memmem(sends, (size_t)(end - sends), ", state ", 8);
  s->from = from;
  s->who = (size_t)(pid_at - from);
  while (s->who && from[s->who - 1] == ' ') s->who--;
  s->pid = atoi(pid_at + 5);
  s->state = st && st + 8 < end ? st[8] : '?';
  return 1;
}

/* The verdict for a child that ended by a signal, from the sender lines already in the file (text = its
 * tail): the lines aimed at this pid since its last "ended" line. A sender in the guest that found it
 * running wins; then this process's own kill while it ran (Chromium's reap after a broken channel);
 * otherwise nobody in the guest ended it. Exposed (hidden) for the host test. */
__attribute__((visibility("hidden"))) void bl_childkill_verdict(const char *text, size_t len, pid_t child, int sig, pid_t self,
                                                                char *out, size_t size, int all) {
  char needle[64], ended[64];
  int k = snprintf(needle, sizeof(needle), " to steamwebhelper pid %d (", (int)child);
  int e = snprintf(ended, sizeof(ended), "): child %d ended ", (int)child);
  const char *begin = text;
  for (const char *p = text; text && e > 0 && (size_t)(p - text) < len;) {
    const char *hit = memmem(p, len - (size_t)(p - text), ended, (size_t)e);
    if (!hit) break;
    begin = hit + e;
    p = begin;
  }
  struct sender other = {0}, mine = {0};
  int have_other = 0, have_mine = 0;
  for (const char *p = begin; text && k > 0 && (size_t)(p - text) < len;) {
    const char *hit = memmem(p, len - (size_t)(p - text), needle, (size_t)k);
    if (!hit) break;
    const char *line = hit;
    while (line > text && line[-1] != '\n') line--;
    const char *end = memchr(hit, '\n', len - (size_t)(hit - text));
    if (!end) end = text + len;
    struct sender s;
    if (parse_sender(line, end, &s)) {
      if (s.pid == (int)self) {
        if (!have_mine || mine.state == 'Z') mine = s;
        have_mine = 1;
      } else {
        if (!have_other || other.state == 'Z') other = s;
        have_other = 1;
      }
    }
    p = end;
  }
  if (have_other && other.state != 'Z')
    snprintf(out, size, "sent by %.*s (pid %d) while it ran", (int)other.who, other.from, other.pid);
  else if (have_mine && mine.state != 'Z')
    snprintf(out, size, "this process ended it while it still ran (Chromium's own kill)");
  else if ((sig == SIGKILL || sig == SIGTERM || sig == SIGINT || sig == SIGHUP || sig == SIGQUIT) && all)
    snprintf(out, size, "nobody in the guest sent it while it ran%s: it came from outside the guest (Android or the kernel)",
             have_mine || have_other ? " (the kills seen came after it had ended)" : "");
  else if (sig == SIGKILL || sig == SIGTERM || sig == SIGINT || sig == SIGHUP || sig == SIGQUIT)
    snprintf(out, size, "no steamwebhelper process sent it while it ran%s: it came from outside Steam's browser (another guest "
             "program, Android or the kernel; BL_WEBHELPER_EXIT_DIAG=all tells a guest sender apart)",
             have_mine || have_other ? " (the kills seen came after it had ended)" : "");
  else
    snprintf(out, size, "nobody %s sent it: it crashed (%s)", all ? "in the guest" : "in Steam's browser", sig_name(sig));
}

static void note_end(pid_t child, int status, int adj) {
  if (!(WIFSIGNALED(status) || (WIFEXITED(status) && WEXITSTATUS(status)))) return;
  char line[1024], me[128], adjs[16], verdict[512], how[64];
  if (WIFSIGNALED(status)) {
    snprintf(how, sizeof(how), "by %s (%d)%s", sig_name(WTERMSIG(status)), WTERMSIG(status), WCOREDUMP(status) ? ", core" : "");
    char path[512], tail[EXIT_DIAG_TAIL];
    ssize_t n = 0;
    log_path(path, sizeof(path));
    int fd = raw_open(path, O_RDONLY | O_CLOEXEC, 0);
    if (fd >= 0) {
      struct stat st;
      off_t from = !fstat(fd, &st) && st.st_size > (off_t)sizeof(tail) ? st.st_size - (off_t)sizeof(tail) : 0;
      n = pread(fd, tail, sizeof(tail), from);
      raw_close(fd);
    }
    bl_childkill_verdict(tail, n > 0 ? (size_t)n : 0, child, WTERMSIG(status), getpid(), verdict, sizeof(verdict), exit_diag_all);
  } else {
    snprintf(how, sizeof(how), "with status %d", WEXITSTATUS(status));
    snprintf(verdict, sizeof(verdict), "it exited by itself");
  }
  self_desc(me, sizeof(me));
  adj_text(adj, adjs, sizeof(adjs));
  int m = stamp(line, sizeof(line));
  m += snprintf(line + m, sizeof(line) - m,
                "droiddeck: %s (pid %d): child %d ended %s; its oom_score_adj %s (this process %d), MemAvailable %ld MB; %s\n",
                me, (int)getpid(), (int)child, how, adjs, pid_adj(getpid()), mem_available_mb(), verdict);
  if (m > 0) log_line(line, (size_t)m < sizeof(line) ? (size_t)m : sizeof(line) - 1);
}

/* Called by gpuexit.c's exit hooks: a steamwebhelper child other than the GPU process (which gpuexit.c
 * reports itself) ending with a non-zero status. */
__attribute__((visibility("hidden"))) void bl_webhelper_exit_note(const char *how, int status, const void *caller, int err);
__attribute__((visibility("hidden"))) void bl_webhelper_exit_note(const char *how, int status, const void *caller, int err) {
  if (!exit_diag_on || !exit_diag_webhelper || !status) return;
  char me[128];
  self_desc(me, sizeof(me));
  if (strstr(me, "gpu-process")) return;
  Dl_info info;
  memset(&info, 0, sizeof(info));
  const char *lib = "?";
  unsigned long off = (unsigned long)caller;
  if (caller && dladdr(caller, &info) && info.dli_fname) {
    lib = strrchr(info.dli_fname, '/') ? strrchr(info.dli_fname, '/') + 1 : info.dli_fname;
    off = (unsigned long)((const char *)caller - (const char *)info.dli_fbase);
  }
  char line[512];
  int m = stamp(line, sizeof(line));
  m += snprintf(line + m, sizeof(line) - m, "droiddeck: %s (pid %d) %s(%d) called from %s+0x%lx, errno %d\n", me, (int)getpid(), how,
                status, lib, off, err);
  if (m > 0) log_line(line, (size_t)m < sizeof(line) ? (size_t)m : sizeof(line) - 1);
}

__attribute__((constructor)) static void exit_diag_init(void) {
  real_kill = (int (*)(pid_t, int))dlsym(RTLD_NEXT, "kill");
  real_killpg = (int (*)(pid_t, int))dlsym(RTLD_NEXT, "killpg");
  real_tgkill = (int (*)(pid_t, pid_t, int))dlsym(RTLD_NEXT, "tgkill");
  real_sigqueue = (int (*)(pid_t, int, const union sigval))dlsym(RTLD_NEXT, "sigqueue");
  real_waitpid = (pid_t (*)(pid_t, int *, int))dlsym(RTLD_NEXT, "waitpid");
  real_wait4 = (pid_t (*)(pid_t, int *, int, struct rusage *))dlsym(RTLD_NEXT, "wait4");
  next_openat = (int (*)(int, const char *, int, ...))dlsym(RTLD_NEXT, "openat");
  next_close = (int (*)(int))dlsym(RTLD_NEXT, "close");
  const char *sw = getenv("BL_WEBHELPER_EXIT_DIAG");
  if (sw && !strcmp(sw, "0")) return;
  int all = sw && !strcmp(sw, "all");
  const char *bridge = getenv("BL_VKBRIDGE");
  if (!(sw && (!strcmp(sw, "1") || all)) && !(bridge && !strcmp(bridge, "1"))) return;
  char buf[512];
  ssize_t n = read_small("/proc/self/cmdline", buf, sizeof(buf));
  if (n > 0) {
    const char *base = strrchr(buf, '/');
    base = base ? base + 1 : buf;
    exit_diag_webhelper = !strcmp(base, "steamwebhelper");
  }
  if (!exit_diag_webhelper && !all) return; /* any other guest process: the calls go straight through */
  time_t now = time(NULL);
  struct tm tm;
  if (localtime_r(&now, &tm)) exit_diag_gmtoff = tm.tm_gmtoff;
  exit_diag_all = all;
  exit_diag_on = 1;
}

int kill(pid_t pid, int sig) {
  int (*real)(pid_t, int) = real_kill ? real_kill : (int (*)(pid_t, int))dlsym(RTLD_NEXT, "kill");
  if (exit_diag_on && bl_childkill_watched(sig)) {
    int saved = errno;
    note_signal("kill", pid, sig);
    errno = saved;
  }
  if (!real) {
    errno = ENOSYS;
    return -1;
  }
  return real(pid, sig);
}

int killpg(pid_t pgrp, int sig) {
  int (*real)(pid_t, int) = real_killpg ? real_killpg : (int (*)(pid_t, int))dlsym(RTLD_NEXT, "killpg");
  if (exit_diag_on && bl_childkill_watched(sig) && pgrp > 0) {
    int saved = errno;
    note_signal("killpg", -pgrp, sig);
    errno = saved;
  }
  if (!real) {
    errno = ENOSYS;
    return -1;
  }
  return real(pgrp, sig);
}

int tgkill(pid_t tgid, pid_t tid, int sig) {
  int (*real)(pid_t, pid_t, int) = real_tgkill ? real_tgkill : (int (*)(pid_t, pid_t, int))dlsym(RTLD_NEXT, "tgkill");
  if (exit_diag_on && bl_childkill_watched(sig) && tgid > 0 && tgid != getpid()) {
    int saved = errno;
    note_signal("tgkill", tgid, sig);
    errno = saved;
  }
  if (!real) return (int)syscall(SYS_tgkill, tgid, tid, sig); /* glibc before 2.30 has no tgkill */
  return real(tgid, tid, sig);
}

int sigqueue(pid_t pid, int sig, const union sigval value) {
  int (*real)(pid_t, int, const union sigval) =
      real_sigqueue ? real_sigqueue : (int (*)(pid_t, int, const union sigval))dlsym(RTLD_NEXT, "sigqueue");
  if (exit_diag_on && bl_childkill_watched(sig) && pid > 0) {
    int saved = errno;
    note_signal("sigqueue", pid, sig);
    errno = saved;
  }
  if (!real) {
    errno = ENOSYS;
    return -1;
  }
  return real(pid, sig, value);
}

pid_t waitpid(pid_t pid, int *status, int options) {
  pid_t (*real)(pid_t, int *, int) = real_waitpid ? real_waitpid : (pid_t (*)(pid_t, int *, int))dlsym(RTLD_NEXT, "waitpid");
  if (!real) {
    errno = ENOSYS;
    return -1;
  }
  if (!exit_diag_on || !exit_diag_webhelper) return real(pid, status, options);
  int saved = errno;
  int adj = pid > 0 ? pid_adj(pid) : NO_ADJ;
  errno = saved;
  int st = 0;
  pid_t r = real(pid, &st, options);
  int err = errno;
  if (r > 0) {
    if (status) *status = st;
    note_end(r, st, r == pid ? adj : NO_ADJ);
  }
  errno = err;
  return r;
}

pid_t wait4(pid_t pid, int *status, int options, struct rusage *usage) {
  pid_t (*real)(pid_t, int *, int, struct rusage *) =
      real_wait4 ? real_wait4 : (pid_t (*)(pid_t, int *, int, struct rusage *))dlsym(RTLD_NEXT, "wait4");
  if (!real) {
    errno = ENOSYS;
    return -1;
  }
  if (!exit_diag_on || !exit_diag_webhelper) return real(pid, status, options, usage);
  int saved = errno;
  int adj = pid > 0 ? pid_adj(pid) : NO_ADJ;
  errno = saved;
  int st = 0;
  pid_t r = real(pid, &st, options, usage);
  int err = errno;
  if (r > 0) {
    if (status) *status = st;
    note_end(r, st, r == pid ? adj : NO_ADJ);
  }
  errno = err;
  return r;
}
