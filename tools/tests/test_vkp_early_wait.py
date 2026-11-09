"""Golden test: the compositor resumes a frame wait that the driver ends early (ledger row 79, fc8f8c6f).

Galaxy Tab S9 FE+ (Android 16, Mali-G68 r38p1): Arm's job-manager driver ends a vkWaitForFences that a
signal interrupts with VK_TIMEOUT (before its timeout) or with an error. The compositor took that as "the
frame is done", reset the fence for the next frame and re-recorded command buffers the GPU still ran: whole
frames came out black and the screen flashed. timed_wait (vk_present.c) now resumes the wait until the fence
signals or the caller's real deadline passes.

This compiles the real timed_wait from the source, with a scripted "driver" and a fake clock (no Vulkan, no
wall clock, nothing read from the host): the driver gives up early N times, the fence signals later.
"""
import re
import shutil
import subprocess
import tempfile
import unittest
from pathlib import Path

REPO = Path(__file__).resolve().parents[2]
SOURCE = REPO / 'app/src/main/cpp/waylandcomp/src/vk_present.c'


def extract_timed_wait(source):
    start = re.search(r'^static VkResult timed_wait\([^)]*\) \{', source, re.MULTILINE)
    assert start, 'timed_wait is missing from vk_present.c'
    cursor, depth = start.end(), 1
    while depth:
        depth += (source[cursor] == '{') - (source[cursor] == '}')
        cursor += 1
    function = source[start.start():cursor]
    # The resume cap is a #define next to the function (absent before the fix).
    define = re.search(r'^#define WAIT_RESUME_CAP_NS[^\n]*$', source, re.MULTILINE)
    return (define.group(0) + '\n' if define else '') + function


PREAMBLE = r'''
#include <assert.h>
#include <stdint.h>
#include <stdio.h>

typedef int VkResult;
typedef void *VkFence;
typedef void *VkDevice;
typedef uint32_t VkBool32;
enum { VK_SUCCESS = 0, VK_NOT_READY = 1, VK_TIMEOUT = 2, VK_TRUE = 1,
       VK_ERROR_OUT_OF_HOST_MEMORY = -1, VK_ERROR_OUT_OF_DEVICE_MEMORY = -2, VK_ERROR_DEVICE_LOST = -4,
       VK_ERROR_UNKNOWN = -13 };

/* scripted driver: the fence signals at sig_ns; the next early_left waits return early_code after 2 ms */
static int64_t now_ns, sig_ns;
static int early_left, early_code, wait_calls, status_calls, log_lines;
static struct { VkResult (*WaitForFences)(VkDevice, uint32_t, const VkFence *, VkBool32, uint64_t);
                VkResult (*GetFenceStatus)(VkDevice, VkFence); } g_vk;
static VkDevice g_dev;
static struct { int64_t wait_ns, wait_max_ns; unsigned waits, early_waits; } g_perf;

static int64_t perf_now(void) { return now_ns; }
static void perf_add(int64_t *sum, int64_t *max, unsigned *count, int64_t d) {
    *sum += d; if (d > *max) *max = d; (*count)++;
}
__attribute__((unused)) static void droiddeck_log(const char *tag, const char *fmt, ...) { (void)tag; (void)fmt; log_lines++; }
__attribute__((unused)) static const char *vk_result_name(VkResult r) { (void)r; return "?"; }

static VkResult fake_wait(VkDevice d, uint32_t n, const VkFence *f, VkBool32 all, uint64_t timeout) {
    wait_calls++;
    if (early_left > 0) { early_left--; now_ns += 2000000; return early_code; }
    if (timeout == UINT64_MAX || now_ns + (int64_t)timeout >= sig_ns) {
        if (sig_ns > now_ns) now_ns = sig_ns;
        return VK_SUCCESS;
    }
    now_ns += (int64_t)timeout;
    return VK_TIMEOUT;
}
static VkResult fake_status(VkDevice d, VkFence f) {
    status_calls++;
    return now_ns >= sig_ns ? VK_SUCCESS : VK_NOT_READY;
}
static void setup(int64_t sig, int early, int code) {
    now_ns = 1000000000LL; sig_ns = now_ns + sig; early_left = early; early_code = code;
    wait_calls = status_calls = 0; g_perf.early_waits = 0;
    g_vk.WaitForFences = fake_wait; g_vk.GetFenceStatus = fake_status;
}
#define MS 1000000LL
'''

CHECKS = r'''
int main(void) {
    VkFence fence = (VkFence)1;
    VkResult r;

    /* the S9 FE+ fingerprint: no deadline, the driver says VK_TIMEOUT after 2 ms three times, the GPU is done at 50 ms */
    setup(50 * MS, 3, VK_TIMEOUT);
    r = timed_wait(fence, UINT64_MAX);
    assert(r == VK_SUCCESS);               /* not "done" on the first early answer */
    assert(wait_calls == 4);
    assert(now_ns - 1000000000LL >= 50 * MS); /* only returned once the fence had signalled */
    assert(g_perf.early_waits == 3);       /* counted for the perf line */

    /* an error code a wait may not return is resumed too */
    setup(40 * MS, 2, VK_ERROR_UNKNOWN);
    r = timed_wait(fence, UINT64_MAX);
    assert(r == VK_SUCCESS && wait_calls == 3);

    /* a real deadline is still honoured: the fence signals long after the caller's 1 s */
    setup(5000 * MS, 0, 0);
    r = timed_wait(fence, 1000000000ULL);
    assert(r == VK_TIMEOUT);
    assert(wait_calls == 1);
    assert(now_ns - 1000000000LL < 1100 * MS);

    /* early answers inside a 1 s wait, the fence signals at 200 ms: success, not an early VK_TIMEOUT */
    setup(200 * MS, 2, VK_TIMEOUT);
    r = timed_wait(fence, 1000000000ULL);
    assert(r == VK_SUCCESS && wait_calls == 3);

    /* the early answer came with the fence already signalled: done at once, no further wait */
    setup(1 * MS, 1, VK_TIMEOUT);
    r = timed_wait(fence, UINT64_MAX);
    assert(r == VK_SUCCESS && wait_calls == 1 && status_calls == 1);

    /* a lost device is returned at once, not resumed */
    setup(50 * MS, 5, VK_ERROR_DEVICE_LOST);
    r = timed_wait(fence, UINT64_MAX);
    assert(r == VK_ERROR_DEVICE_LOST && wait_calls == 1);

    /* a driver that never signals: with no deadline the driver's answer stands after 30 s (no endless loop) */
    setup(1000000LL * 1000000, 1000000, VK_TIMEOUT);
    r = timed_wait(fence, UINT64_MAX);
    assert(r == VK_TIMEOUT);
    assert(now_ns - 1000000000LL >= 30000 * MS && now_ns - 1000000000LL < 30100 * MS);

    /* one log line the first time, not one per wait */
    assert(log_lines == 1);
    puts("OK");
    return 0;
}
'''


class EarlyFrameWaitTest(unittest.TestCase):
    def test_a_wait_the_driver_ends_early_is_resumed_until_the_fence_signals(self):
        cc = shutil.which('cc')
        if not cc:
            self.skipTest('A host C compiler is required')
        harness = PREAMBLE + extract_timed_wait(SOURCE.read_text()) + CHECKS
        with tempfile.TemporaryDirectory() as tmp:
            tmp = Path(tmp)
            (tmp / 'main.c').write_text(harness)
            exe = tmp / 'early_wait'
            build = subprocess.run([cc, '-std=gnu11', '-Wall', '-Wextra', '-Werror', '-Wno-unused-parameter',
                                    str(tmp / 'main.c'), '-o', str(exe)], capture_output=True, text=True)
            self.assertEqual(build.returncode, 0, build.stderr)
            run = subprocess.run([str(exe)], capture_output=True, text=True, timeout=30)
            self.assertEqual(run.returncode, 0, run.stdout + run.stderr)
            self.assertIn('OK', run.stdout)


if __name__ == '__main__':
    unittest.main()
