"""libblsession zram.c mode 2: the page-out that runs while a game does (one 2.07 s pass at full
priority on any core, in the RE7 run's worst 10 s). Each SIGURG does one slice, at SCHED_IDLE on the cores
the control file names, in steps of 512 KB, and none while gamescope's frame ring shows the game in a bad
stretch; the thread gets its own cores and policy back after every slice."""
import os
from pathlib import Path
import resource
import shutil
import subprocess
import tempfile
import unittest

PRELOAD = Path(__file__).resolve().parents[1] / "linuxfs/preload"

PROGRAM = r"""
#define _GNU_SOURCE
#include <fcntl.h>
#include <pthread.h>
#include <sched.h>
#include <signal.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/mman.h>
#include <sys/syscall.h>
#include <time.h>
#include <unistd.h>

static pid_t main_tid;
static volatile int stop, saw_idle, saw_mask, wanted_cpu;
static char frames_path[512];

static long status(const char *key) {
  FILE *f = fopen("/proc/self/status", "r");
  char line[256];
  long v = -1;
  while (f && fgets(line, sizeof(line), f))
    if (!strncmp(line, key, strlen(key))) v = atol(line + strlen(key));
  if (f) fclose(f);
  return v;
}

// field 41 of /proc/self/task/<tid>/stat
static int policy_of(pid_t tid) {
  char path[64], buf[1024];
  snprintf(path, sizeof(path), "/proc/self/task/%d/stat", tid);
  int fd = open(path, O_RDONLY);
  if (fd < 0) return -1;
  long n = read(fd, buf, sizeof(buf) - 1);
  close(fd);
  if (n <= 0) return -1;
  buf[n] = 0;
  char *p = strrchr(buf, ')');
  for (int field = 2; p && field < 41; field++) p = strchr(p + 1, ' ');
  return p ? atoi(p + 1) : -1;
}

static void cpus_of(pid_t tid, char *out, size_t size) {
  char path[64], line[256];
  snprintf(path, sizeof(path), "/proc/self/task/%d/status", tid);
  FILE *f = fopen(path, "r");
  out[0] = 0;
  while (f && fgets(line, sizeof(line), f))
    if (!strncmp(line, "Cpus_allowed_list:", 18)) {
      char *v = line + 18;
      while (*v == ' ' || *v == '\t') v++;
      v[strcspn(v, "\n")] = 0;
      snprintf(out, size, "%s", v);
    }
  if (f) fclose(f);
}

static void *watch(void *arg) {
  (void)arg;
  char want[16], now[256];
  snprintf(want, sizeof(want), "%d", wanted_cpu);
  while (!stop) {
    if (policy_of(main_tid) == SCHED_IDLE) saw_idle = 1;
    cpus_of(main_tid, now, sizeof(now));
    if (!strcmp(now, want)) saw_mask = 1;
  }
  return NULL;
}

static long ns(clockid_t clock) {
  struct timespec t;
  clock_gettime(clock, &t);
  return t.tv_sec * 1000000000L + t.tv_nsec;
}

// gamescope's ring (patch 0125): 64 frames of 16 ms; with `late`, the newest is 100 ms old (a hitch now).
static void write_frames(int late) {
  if (!frames_path[0]) return;
  unsigned char buf[128 + 64 * 16];
  memset(buf, 0, sizeof(buf));
  uint32_t head[3] = {0x46484444u, 1, 64};
  memcpy(buf, head, sizeof(head));
  uint64_t count = 64;
  memcpy(buf + 16, &count, 8);
  long now = ns(CLOCK_MONOTONIC) - (late ? 100000000L : 1000000L);
  for (int i = 0; i < 64; i++) {
    uint64_t frame[2] = {16000000ULL, (uint64_t)(now - (63 - i) * 16000000L)};
    memcpy(buf + 128 + i * 16, frame, 16);
  }
  int fd = open(frames_path, O_WRONLY | O_CREAT | O_TRUNC, 0644);
  if (fd < 0 || write(fd, buf, sizeof(buf)) != (long)sizeof(buf)) exit(5);
  close(fd);
}

static int acked(const char *ctl, char *ack, size_t size) {
  char path[600];
  snprintf(path, sizeof(path), "%s.ack.%d", ctl, getpid());
  int fd = open(path, O_RDONLY);
  ack[0] = 0;
  if (fd < 0) return 0;
  long n = read(fd, ack, size - 1);
  close(fd);
  ack[n > 0 ? n : 0] = 0;
  return ack[0] == '1';
}

int main(int argc, char **argv) {
  const char *ctl = argv[1];
  size_t size = strtoul(argv[2], NULL, 0) << 20;
  int bad_signals = atoi(argv[3]);
  snprintf(frames_path, sizeof(frames_path), "%s", argc > 4 ? argv[4] : "");
  main_tid = (pid_t)syscall(SYS_gettid);
  cpu_set_t own;
  sched_getaffinity(0, sizeof(own), &own);
  for (int c = 0; c < CPU_SETSIZE; c++) if (CPU_ISSET(c, &own)) wanted_cpu = c;
  char before_cpus[256];
  cpus_of(main_tid, before_cpus, sizeof(before_cpus));
  unsigned char *data = mmap(NULL, size, PROT_READ | PROT_WRITE, MAP_PRIVATE | MAP_ANONYMOUS, -1, 0);
  for (size_t i = 0; i < size; i++) data[i] = (unsigned char)(i * 7 + i / 4096);
  int fd = open(ctl, O_WRONLY | O_TRUNC);
  dprintf(fd, "2 pass=7 cpus=%d frames=%s", wanted_cpu, frames_path);
  close(fd);
  pthread_t t;
  pthread_create(&t, NULL, watch, NULL);
  char ack[128];
  long max_cpu = 0, signals = 0, worked = 0;
  long swap_bad = -1;
  for (; signals < 4000 && !acked(ctl, ack, sizeof(ack)); signals++) {
    if (signals == bad_signals) swap_bad = status("VmSwap:");
    if (signals < bad_signals) write_frames(1);
    else write_frames(0);  // a game presenting every 16 ms
    long c0 = ns(CLOCK_THREAD_CPUTIME_ID);
    kill(getpid(), SIGURG);  // the session sends kill (SI_USER); this thread takes it
    long c = ns(CLOCK_THREAD_CPUTIME_ID) - c0;
    if (c > max_cpu) max_cpu = c;
    worked++;
  }
  // A signal for a pass that is done does nothing.
  long swap_done = status("VmSwap:");
  kill(getpid(), SIGURG);  // the session sends kill (SI_USER); this thread takes it
  char after_ack[128];
  acked(ctl, after_ack, sizeof(after_ack));
  stop = 1;
  pthread_join(t, NULL);
  char after_cpus[256];
  cpus_of(main_tid, after_cpus, sizeof(after_cpus));
  // What one direct page-out of a same-size area gets on this machine right now (its swap may be full).
  unsigned char *probe = mmap(NULL, size, PROT_READ | PROT_WRITE, MAP_PRIVATE | MAP_ANONYMOUS, -1, 0);
  for (size_t i = 0; i < size; i++) probe[i] = (unsigned char)(i * 13 + i / 4096);
  long swap_before_probe = status("VmSwap:");
  madvise(probe, size, 21);
  printf("swap_direct %ld\n", status("VmSwap:") - swap_before_probe);
  printf("signals %ld\nmax_cpu_us %ld\nswap_bad %ld\nswap_done %ld\n", signals, max_cpu / 1000, swap_bad, swap_done);
  printf("saw_idle %d\nsaw_mask %d\npolicy_after %d\nmask_same %d\nack_same %d\n", saw_idle, saw_mask,
         policy_of(main_tid), !strcmp(before_cpus, after_cpus), !strcmp(ack, after_ack));
  printf("ack %s\n", ack);
  return 0;
}
"""


def swap_free_kb():
    values = {}
    for line in Path("/proc/meminfo").read_text().splitlines():
        key, _, rest = line.partition(":")
        values[key] = int(rest.split()[0])
    return values.get("SwapFree", 0)


@unittest.skipUnless(shutil.which("cc"), "needs a C compiler")
class ZramGentleTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.dir = Path(tempfile.mkdtemp())
        cls.ctl = cls.dir / "ctl"
        (cls.dir / "program.c").write_text(PROGRAM)
        sources = [str(p) for p in sorted(PRELOAD.glob("*.c"))]
        cls.libs = {}
        # The default slice (40 ms), and a 3 ms one so a small test area still takes many slices.
        for name, extra in (("default", []), ("short", ["-DZR_SLICE_NS=3000000L"])):
            lib = cls.dir / name / "libblsession.so"
            lib.parent.mkdir()
            subprocess.run(["cc", "-shared", "-fPIC", "-O2", "-Wall", "-pthread", "-DZR_CTL=\"%s\"" % cls.ctl, *extra,
                            "-o", str(lib), *sources, "-ldl"], check=True)
            cls.libs[name] = lib
        cls.program = cls.dir / "program"
        subprocess.run(["cc", "-O2", "-pthread", "-o", str(cls.program), str(cls.dir / "program.c")], check=True)

    @classmethod
    def tearDownClass(cls):
        shutil.rmtree(cls.dir, True)

    def setUp(self):
        self.ctl.write_text("0")
        for ack in self.dir.glob("ctl.ack.*"):
            ack.unlink()

    def run_program(self, lib="short", mb=48, bad=0, frames=True):
        args = [str(self.program), str(self.ctl), str(mb), str(bad)]
        if frames:
            args.append(str(self.dir / "frames"))
        result = subprocess.run(args, env=dict(os.environ, LD_PRELOAD=str(self.libs[lib])),
                                capture_output=True, text=True, check=True, timeout=300)
        out = {}
        for line in result.stdout.splitlines():
            key, _, value = line.partition(" ")
            out[key] = value
        return out

    def test_the_pass_goes_in_slices_and_says_so_when_done(self):
        out = self.run_program()
        self.assertGreater(int(out["signals"]), 3, out)
        fields = out["ack"].split()
        self.assertEqual(fields[0], "1", out)
        self.assertEqual(len(fields), 4, out)
        self.assertEqual(int(fields[1]), int(out["signals"]), out)
        self.assertEqual(out["ack_same"], "1", out)  # a signal after the end starts nothing

    def test_each_slice_runs_idle_on_the_named_cores_and_gives_them_back(self):
        out = self.run_program()
        self.assertEqual(out["saw_mask"], "1", out)
        self.assertEqual(out["mask_same"], "1", out)
        self.assertEqual(out["policy_after"], "0", out)
        # SCHED_IDLE only where the thread may leave it again: RLIMIT_NICE >= 20 - nice (Android: 40).
        soft = resource.getrlimit(resource.RLIMIT_NICE)[0]
        can_return = soft == resource.RLIM_INFINITY or soft >= 20 - os.nice(0)
        self.assertEqual(out["saw_idle"], "1" if can_return else "0", out)

    def test_no_signal_holds_a_core_longer_than_a_slice(self):
        out = self.run_program("default", mb=96)
        # 40 ms of slice plus one 512 KB step; the bound is on the thread's own CPU time, so a busy
        # machine cannot make it fail (it only makes the slices do less).
        self.assertLess(int(out["max_cpu_us"]), 60000, out)

    def assert_paged_out(self, out):
        # As much as one direct MADV_PAGEOUT of a same-size area gets on this machine now: a PC with a
        # full swap pages out little either way, and that is not this code's doing.
        direct = int(out["swap_direct"])
        if direct < 8 * 1024:
            self.skipTest("this machine's swap takes almost nothing now (%d KB)" % direct)
        self.assertGreater(int(out["swap_done"]), direct // 2, out)

    def test_it_pages_out_and_stops_while_the_game_is_in_a_hitch(self):
        out = self.run_program(mb=48, bad=6)
        self.assertLess(int(out["swap_bad"]), 1024, out)  # six signals in a hitch: nothing paged out
        self.assertGreaterEqual(int(out["ack"].split()[2]), 6, out)  # slices ended for the frames
        self.assert_paged_out(out)

    def test_without_a_frame_ring_it_still_pages_out(self):
        out = self.run_program(mb=48, frames=False)
        self.assertEqual(out["ack"].split()[0], "1", out)
        self.assert_paged_out(out)


if __name__ == "__main__":
    unittest.main()
