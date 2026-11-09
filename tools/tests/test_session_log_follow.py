"""Golden test: the compositor's wayland.log follows each session (ledger row 97, 71d800f3b).

The compositor starts once per app process and outlived its session, so every later session's lines went on
into the first session's wayland.log and the later session's directory had none (Send logs for a second
game showed an empty compositor log). droiddeck_session_log_follow moves the native log to the new
session's file; the old file ends with a pointer, the new header names the earlier log.

This compiles the real log code from compositor.c (droiddeck_log, session_log_file, open_session_log,
droiddeck_session_log_follow) on the host, with BL_WAYLAND_LOG and every file inside a temp directory.
"""
import shutil
import subprocess
import tempfile
import unittest
from pathlib import Path

REPO = Path(__file__).resolve().parents[2]
SOURCE = REPO / 'app/src/main/cpp/waylandcomp/src/compositor.c'

PREAMBLE = r'''
#define _GNU_SOURCE
#include <assert.h>
#include <errno.h>
#include <pthread.h>
#include <stdarg.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/stat.h>
#include <time.h>
#include <unistd.h>
#define WLOGI(...) ((void)0)
#define WLOGE(...) ((void)0)
#define SESSION_LOG_DIR "/nonexistent/never-used-BL_WAYLAND_LOG-is-set"
'''

CHECKS = r'''
static char *slurp(const char *path) {
    FILE *f = fopen(path, "r");
    assert(f);
    static char buf[1 << 16];
    size_t n = fread(buf, 1, sizeof(buf) - 1, f);
    buf[n] = 0;
    fclose(f);
    return buf;
}

int main(int argc, char **argv) {
    assert(argc == 3);
    const char *first = argv[1], *second = argv[2];

    /* before the compositor has started: nothing is opened, the lines wait */
    droiddeck_session_log_follow(second);
    assert(access(second, F_OK) != 0);
    droiddeck_log("test", "early line");

    setenv("BL_WAYLAND_LOG", first, 1);
    open_session_log();
    droiddeck_log("test", "line one (first session)");
    assert(strstr(slurp(first), "early line") && strstr(slurp(first), "line one (first session)"));

    /* the second session: the log moves */
    droiddeck_session_log_follow(second);
    droiddeck_log("test", "line two (second session)");
    char *a = strdup(slurp(first));
    char *b = strdup(slurp(second));
    assert(strstr(a, "line one") && !strstr(a, "line two"));          /* the old log stops */
    assert(strstr(a, "(the log continues in ") && strstr(a, second)); /* and says where to go on */
    assert(strstr(b, "line two (second session)") && !strstr(b, "line one"));
    assert(strstr(b, "The compositor kept running from an earlier session; its log until now: "));
    assert(strstr(b, first));

    /* the same path again, or none: nothing changes */
    droiddeck_session_log_follow(second);
    droiddeck_session_log_follow("");
    droiddeck_session_log_follow(NULL);
    droiddeck_log("test", "line three");
    assert(strstr(slurp(second), "line three"));
    assert(!strstr(slurp(first), "line three"));
    puts("OK");
    return 0;
}
'''


def log_code(source):
    start = source.index('static FILE *g_log;')
    end = source.index('static int log_budget(')
    return source[start:end]


class SessionLogFollowTest(unittest.TestCase):
    def test_a_later_session_gets_its_own_compositor_log(self):
        cc = shutil.which('cc')
        if not cc:
            self.skipTest('A host C compiler is required')
        with tempfile.TemporaryDirectory() as tmp:
            tmp = Path(tmp)
            (tmp / 'main.c').write_text(PREAMBLE + log_code(SOURCE.read_text()) + CHECKS)
            exe = tmp / 'follow'
            build = subprocess.run([cc, '-std=gnu11', '-Wall', '-Wextra', '-Werror', '-Wno-unused-parameter',
                                    '-Wno-unused-function', str(tmp / 'main.c'), '-o', str(exe), '-lpthread'],
                                   capture_output=True, text=True)
            self.assertEqual(build.returncode, 0, build.stderr)
            run = subprocess.run([str(exe), str(tmp / 'wayland-1.log'), str(tmp / 'wayland-2.log')],
                                 capture_output=True, text=True, timeout=30, cwd=tmp)
            self.assertEqual(run.returncode, 0, run.stdout + run.stderr)
            self.assertIn('OK', run.stdout)

    def test_the_java_side_hands_the_new_file_to_the_native_log(self):
        java = (REPO / 'app/src/main/java/com/droiddeck/launcher/wayland/WaylandCompositor.java').read_text()
        body = java[java.index('public static void setSessionLogFile('):]
        body = body[:body.index('\n    }\n')]
        self.assertIn('nativeSetSessionLog(file.getPath())', body)
        jni = (REPO / 'app/src/main/cpp/waylandcomp/src/waylandcomp_jni.c').read_text()
        self.assertIn('Java_com_droiddeck_launcher_wayland_WaylandCompositor_nativeSetSessionLog', jni)
        self.assertIn('droiddeck_session_log_follow(s)', jni)


if __name__ == '__main__':
    unittest.main()
