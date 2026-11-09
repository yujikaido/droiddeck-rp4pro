import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest

PRELOAD = Path(__file__).resolve().parents[1] / "linuxfs/preload"

PROGRAM = r"""
#define _GNU_SOURCE
#include <fcntl.h>
#include <pthread.h>
#include <signal.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/mman.h>
#include <unistd.h>

static const char *ctl;
static size_t size, page;
static volatile int stop;
static unsigned char *shared;

extern long mock_vmswap_kb(void) __attribute__((weak));

static long status(const char *key) {
  if (mock_vmswap_kb && !strcmp(key, "VmSwap:")) return mock_vmswap_kb();
  FILE *f = fopen("/proc/self/status", "r");
  char line[256];
  long v = -1;
  while (f && fgets(line, sizeof(line), f))
    if (!strncmp(line, key, strlen(key))) v = atol(line + strlen(key));
  if (f) fclose(f);
  return v;
}

static void request(char mode) {
  int fd = open(ctl, O_WRONLY | O_TRUNC);
  if (fd < 0 || write(fd, &mode, 1) != 1) exit(3);
  close(fd);
  kill(getpid(), SIGURG);
}

static uint64_t sum(const unsigned char *p, size_t n) {
  uint64_t s = 0;
  for (size_t i = 0; i < n; i += 64) s = s * 31 + p[i];
  return s;
}

static void *writer(void *arg) {
  unsigned char *mine = shared + (size_t)(uintptr_t)arg * (size / 4);
  for (unsigned char round = 1; !stop; round++) {
    for (size_t i = 0; i < size / 4; i += page) {
      if (round > 1 && mine[i] != (unsigned char)(round - 1)) {
        printf("corrupt\n");
        exit(4);
      }
      mine[i] = round;
    }
  }
  return NULL;
}

int main(int argc, char **argv) {
  ctl = argv[1];
  size = strtoul(argv[2], NULL, 0) << 20;
  int cycles = atoi(argv[3]);
  page = (size_t)sysconf(_SC_PAGESIZE);
  unsigned char *data = mmap(NULL, size, PROT_READ | PROT_WRITE, MAP_PRIVATE | MAP_ANONYMOUS, -1, 0);
  for (size_t i = 0; i < size; i++) data[i] = (unsigned char)(i * 7 + i / 4096);
  uint64_t before = sum(data, size);
  shared = mmap(NULL, size, PROT_READ | PROT_WRITE, MAP_PRIVATE | MAP_ANONYMOUS, -1, 0);
  memset(shared, 0, size);
  pthread_t t[2];
  for (uintptr_t i = 0; i < 2; i++) pthread_create(&t[i], NULL, writer, (void *)i);
  struct sigaction sa;
  sigaction(SIGURG, NULL, &sa);
  printf("handler %d\n", (sa.sa_flags & SA_SIGINFO) ? 1 : 0);
  if (argc > 4) {
    unsigned char *ro = mmap(NULL, size, PROT_READ | PROT_WRITE, MAP_PRIVATE | MAP_ANONYMOUS, -1, 0);
    memset(ro, 0x5a, size);
    mprotect(ro, size, PROT_READ);
    madvise(ro, size, 21);
    printf("elsewhere %ld\n", status("VmSwap:"));
    request('0');
    printf("back %ld\n", status("VmSwap:"));
  }
  for (int c = 0; c < cycles; c++) {
    request('1');
    printf("out %ld\n", status("VmSwap:"));
    request('0');
    printf("back %ld\n", status("VmSwap:"));
  }
  stop = 1;
  for (int i = 0; i < 2; i++) pthread_join(t[i], NULL);
  printf("intact %d\n", sum(data, size) == before);
  return 0;
}
"""

MOCK = r"""
#define _GNU_SOURCE
// A stand-in for the part of the kernel the preload talks to, so the tests do not depend on the PC's swap
// (a real MADV_PAGEOUT only swaps while the swap device has room: a busy PC with full swap pages nothing out).
// Loaded with LD_PRELOAD ahead of the preload. The preload fetches `syscall` with dlsym(libc, "syscall"), so
// this library answers that dlsym with its own syscall: madvise(MADV_PAGEOUT) marks pages swapped,
// /proc/self/pagemap shows the mark (bit 62), process_vm_readv (the read-back) clears it; every other call
// goes on to the real syscall. The program asks mock_vmswap_kb() for its VmSwap.
#include <dlfcn.h>
#include <errno.h>
#include <fcntl.h>
#include <pthread.h>
#include <stdint.h>
#include <string.h>
#include <sys/mman.h>
#include <sys/syscall.h>
#include <sys/uio.h>
#include <unistd.h>

#define PAGEOUT 21
#define LEAVES 64
#define LEAF_PAGES 32768

static void *(*real_dlsym)(void *, const char *);
static long (*real_syscall)(long, ...);
static pthread_mutex_t lock = PTHREAD_MUTEX_INITIALIZER;
static struct { uint64_t key; uint8_t bits[LEAF_PAGES / 8]; } leaves[LEAVES];
static int nleaves;
static long swapped_pages;
static int pagemap_fd = -1;
static __thread uint64_t pagemap_pos;

static unsigned long page_size(void) {
  static unsigned long p;
  if (!p) p = (unsigned long)sysconf(_SC_PAGESIZE);
  return p;
}

static uint8_t *bit_of(uint64_t page, int create, uint8_t *mask) {
  uint64_t key = page / LEAF_PAGES, off = page % LEAF_PAGES;
  for (int i = 0; i < nleaves; i++)
    if (leaves[i].key == key) { *mask = (uint8_t)(1u << (off & 7)); return &leaves[i].bits[off >> 3]; }
  if (!create || nleaves == LEAVES) return NULL;
  leaves[nleaves].key = key;
  *mask = (uint8_t)(1u << (off & 7));
  return &leaves[nleaves++].bits[off >> 3];
}

static int page_get(uint64_t page) {
  uint8_t m, *b = bit_of(page, 0, &m);
  return b && (*b & m);
}

static void page_set(uint64_t page, int on) {
  uint8_t m, *b = bit_of(page, on, &m);
  if (!b || !!(*b & m) == on) return;
  if (on) { *b |= m; swapped_pages++; } else { *b &= (uint8_t)~m; swapped_pages--; }
}

long mock_vmswap_kb(void) {
  pthread_mutex_lock(&lock);
  long kb = swapped_pages * (long)(page_size() / 1024);
  pthread_mutex_unlock(&lock);
  return kb;
}

static void pageout(unsigned long addr, unsigned long len) {
  unsigned long ps = page_size();
  pthread_mutex_lock(&lock);
  for (unsigned long a = addr & ~(ps - 1); a < addr + len; a += ps) page_set(a / ps, 1);
  pthread_mutex_unlock(&lock);
}

static long mock_syscall(long n, long a, long b, long c, long d, long e, long f) {
  unsigned long ps = page_size();
  if (n == SYS_madvise && c == PAGEOUT) {
    pageout((unsigned long)a, (unsigned long)b);
    return 0;
  }
  if (n == SYS_openat && b && !strcmp((const char *)b, "/proc/self/pagemap")) {
    long fd = real_syscall(SYS_openat, a, "/dev/null", O_RDONLY | O_CLOEXEC, 0);
    if (fd >= 0) pagemap_fd = (int)fd;
    return fd;
  }
  if (n == SYS_close && a == pagemap_fd) pagemap_fd = -1;
  if (n == SYS_lseek && a == pagemap_fd && pagemap_fd >= 0) {
    pagemap_pos = (uint64_t)b;
    return b;
  }
  if (n == SYS_read && a == pagemap_fd && pagemap_fd >= 0) {
    uint64_t *out = (uint64_t *)b;
    unsigned long count = (unsigned long)c / sizeof(uint64_t);
    pthread_mutex_lock(&lock);
    for (unsigned long i = 0; i < count; i++) out[i] = page_get(pagemap_pos / sizeof(uint64_t) + i) ? (1ULL << 62) : 0;
    pthread_mutex_unlock(&lock);
    return (long)(count * sizeof(uint64_t));
  }
  long r = real_syscall(n, a, b, c, d, e, f);
  if (n == SYS_process_vm_readv && r > 0) {
    const struct iovec *remote = (const struct iovec *)d;
    pthread_mutex_lock(&lock);
    for (long i = 0; i < (long)e && i < r; i++)
      for (unsigned long x = (unsigned long)remote[i].iov_base & ~(ps - 1); x < (unsigned long)remote[i].iov_base + remote[i].iov_len; x += ps)
        page_set(x / ps, 0);
    pthread_mutex_unlock(&lock);
  }
  return r;
}

static void resolve(void) {
  if (real_dlsym) return;
  real_dlsym = (void *(*)(void *, const char *))dlvsym(RTLD_NEXT, "dlsym", "GLIBC_2.34");
  if (!real_dlsym) real_dlsym = (void *(*)(void *, const char *))dlvsym(RTLD_NEXT, "dlsym", "GLIBC_2.2.5");
  real_syscall = (long (*)(long, ...))real_dlsym(RTLD_DEFAULT, "syscall");
}

void *dlsym(void *handle, const char *name) {
  resolve();
  if (!strcmp(name, "syscall") && handle != RTLD_NEXT && handle != RTLD_DEFAULT) return (void *)mock_syscall;
  return real_dlsym(handle, name);
}

// The program's own madvise(MADV_PAGEOUT) (libc's madvise does not go through syscall()).
int madvise(void *addr, size_t len, int advice) {
  resolve();
  if (advice == PAGEOUT) {
    pageout((unsigned long)addr, len);
    return 0;
  }
  return (int)real_syscall(SYS_madvise, addr, len, advice);
}
"""


def swap_free_kb():
    for line in Path("/proc/meminfo").read_text().splitlines():
        if line.startswith("SwapFree:"):
            return int(line.split()[1])
    return 0


# The tests of the page-out and the read-back run against a stand-in for the kernel's part (MOCK above), so they
# give the same answer on a PC whose swap is full, busy or absent. The same two tests against the real kernel
# (it must have swap with room, and a busy PC may not page out what it is asked to) run with BL_TEST_REAL_SWAP=1.
REAL_SWAP = os.environ.get("BL_TEST_REAL_SWAP") == "1" and swap_free_kb() > 512 * 1024


@unittest.skipUnless(shutil.which("cc"), "needs a C compiler")
class ZramPreloadTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.dir = Path(tempfile.mkdtemp())
        cls.ctl = cls.dir / "ctl"
        build = cls.dir / "build"
        build.mkdir()
        (build / "program.c").write_text(PROGRAM)
        cls.lib = build / "libblsession.so"
        subprocess.run(["cc", "-shared", "-fPIC", "-O2", "-Wall", "-pthread", "-DZR_CTL=\"%s\"" % cls.ctl,
                        "-o", str(cls.lib), *map(str, sorted(PRELOAD.glob("*.c"))), "-ldl"], check=True)
        (build / "mock.c").write_text(MOCK)
        cls.mock = build / "libmockkernel.so"
        subprocess.run(["cc", "-shared", "-fPIC", "-O2", "-Wall", "-pthread", "-o", str(cls.mock), str(build / "mock.c"), "-ldl"], check=True)
        cls.steam = cls.dir / "steam"
        subprocess.run(["cc", "-O2", "-pthread", "-o", str(cls.steam), str(build / "program.c")], check=True)
        cls.game = cls.dir / "game"
        shutil.copy(cls.steam, cls.game)

    @classmethod
    def tearDownClass(cls):
        shutil.rmtree(cls.dir, True)

    def setUp(self):
        self.ctl.write_text("0")
        for ack in self.dir.glob("ctl.ack.*"):
            ack.unlink()

    def run_program(self, program=None, mb=48, cycles=1, *extra, mock=False):
        # the mock goes first: its dlsym answers the preload's lookup of syscall
        environment = dict(os.environ, LD_PRELOAD=(str(self.mock) + ":" if mock else "") + str(self.lib))
        result = subprocess.run([str(program or self.steam), str(self.ctl), str(mb), str(cycles), *extra], env=environment,
                                capture_output=True, text=True, check=True, timeout=600)
        out = {}
        for line in result.stdout.splitlines():
            key, value = line.split()
            out.setdefault(key, []).append(int(value))
        return out

    def test_every_process_takes_the_signal(self):
        self.assertEqual([1], self.run_program()["handler"])
        self.assertEqual([1], self.run_program(self.game)["handler"])
        self.assertEqual(2, len(list(self.dir.glob("ctl.ack.*"))))

    def test_nothing_is_installed_without_the_control_file(self):
        self.ctl.unlink()
        try:
            self.assertEqual([0], self.run_program(cycles=0)["handler"])
        finally:
            self.ctl.write_text("0")

    def pages_out_and_reads_back(self, mock):
        out = self.run_program(mb=48, mock=mock)
        self.assertGreater(out["out"][0], 32 * 1024)
        self.assertLess(out["back"][0], out["out"][0] // 4)
        self.assertEqual([1], out["intact"])
        self.assertEqual(["1"], [ack.read_text() for ack in self.dir.glob("ctl.ack.*")])

    def reads_back_what_the_kernel_swapped_elsewhere(self, mock):
        out = self.run_program(None, 32, 0, "read-only", mock=mock)
        self.assertGreater(out["elsewhere"][0], 16 * 1024)
        self.assertLess(out["back"][0], out["elsewhere"][0] // 4)

    def test_pages_out_and_reads_back(self):
        self.pages_out_and_reads_back(True)

    def test_reads_back_what_the_kernel_swapped_elsewhere(self):
        self.reads_back_what_the_kernel_swapped_elsewhere(True)

    @unittest.skipUnless(REAL_SWAP, "real swap: BL_TEST_REAL_SWAP=1 and a swap device with room")
    def test_pages_out_and_reads_back_real_kernel(self):
        self.pages_out_and_reads_back(False)

    @unittest.skipUnless(REAL_SWAP, "real swap: BL_TEST_REAL_SWAP=1 and a swap device with room")
    def test_reads_back_what_the_kernel_swapped_elsewhere_real_kernel(self):
        self.reads_back_what_the_kernel_swapped_elsewhere(False)

    def test_memory_stays_intact_under_writers(self):
        out = self.run_program(mb=32, cycles=6)
        self.assertEqual([1], out["intact"])
        self.assertEqual(6, len(out["out"]))


if __name__ == "__main__":
    unittest.main()
