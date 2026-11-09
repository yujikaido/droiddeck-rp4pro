#define _GNU_SOURCE
#include <dlfcn.h>
#include <errno.h>
#include <fcntl.h>
#include <pthread.h>
#include <sched.h>
#include <signal.h>
#include <stdint.h>
#include <string.h>
#include <sys/mman.h>
#include <sys/resource.h>
#include <sys/syscall.h>
#include <sys/uio.h>
#include <time.h>
#include <unistd.h>


#ifndef MADV_PAGEOUT
#define MADV_PAGEOUT 21
#endif
#ifndef ZR_CTL
#define ZR_CTL "/tmp/.bl-zram"
#endif
#define ZR_SWAPPED (1ULL << 62)
#define ZR_BATCH 512
// Mode 2, a page-out while a game runs: each SIGURG does at most ZR_SLICE_NS of it, ZR_STEP bytes per
// madvise, on the cores the control file names and at SCHED_IDLE; the session signals again until done.
#ifndef ZR_STEP
#define ZR_STEP (512UL << 10)
#endif
#ifndef ZR_SLICE_NS
#define ZR_SLICE_NS (40L * 1000000L)
#endif
#define ZR_MASK_WORDS 16
#define ZR_FRAMES_MAGIC 0x46484444u
#define ZR_FRAMES_HEADER 128
#define ZR_FRAMES_LOOK 64

static long (*zr_sys)(long, ...);
static unsigned long zr_page;
static int zr_busy;
static int zr_pending;
static char zr_maps[8192];
static uint64_t zr_entries[ZR_BATCH];
static struct iovec zr_iov[ZR_BATCH];
static char zr_sink[ZR_BATCH];
static char zr_ctl[256];
static unsigned long zr_cpus[ZR_MASK_WORDS];
static int zr_have_cpus;
static char zr_frames_path[160];
static unsigned long zr_cursor;
static unsigned long zr_pass, zr_ctl_pass;
static int zr_pass_state;  // mode 2: 0 none, 1 under way, 2 done
static long zr_slices, zr_paused, zr_busy_ms;

static int zr_open(const char *path) {
  return (int)zr_sys(SYS_openat, AT_FDCWD, path, O_RDONLY | O_CLOEXEC, 0);
}

// "cpus=0-3,6": the cores of a mode-2 page-out.
static int zr_parse_cpus(const char *p, const char *end) {
  memset(zr_cpus, 0, sizeof(zr_cpus));
  int any = 0;
  while (p < end && *p >= '0' && *p <= '9') {
    unsigned long first = 0, last;
    while (p < end && *p >= '0' && *p <= '9') first = first * 10 + (unsigned long)(*p++ - '0');
    last = first;
    if (p < end && *p == '-') {
      p++;
      last = 0;
      while (p < end && *p >= '0' && *p <= '9') last = last * 10 + (unsigned long)(*p++ - '0');
    }
    for (unsigned long c = first; c <= last && c < ZR_MASK_WORDS * 8 * sizeof(unsigned long); c++) {
      zr_cpus[c / (8 * sizeof(unsigned long))] |= 1UL << (c % (8 * sizeof(unsigned long)));
      any = 1;
    }
    if (p < end && *p == ',') p++;
    else break;
  }
  return any;
}

// The control file: "0" read back, "1" page out now (suspend), "2 cpus=<list> frames=<path>" page out
// gently while a game runs.
static int zr_mode(void) {
  int fd = zr_open(ZR_CTL);
  if (fd < 0) return -1;
  long n = zr_sys(SYS_read, fd, zr_ctl, sizeof(zr_ctl) - 1);
  zr_sys(SYS_close, fd);
  if (n < 1) return -1;
  zr_ctl[n] = 0;
  char c = zr_ctl[0];
  if (c != '0' && c != '1' && c != '2') return -1;
  if (c == '2') {
    zr_have_cpus = 0;
    zr_frames_path[0] = 0;
    zr_ctl_pass = 0;
    for (char *p = zr_ctl + 1; *p;) {
      while (*p == ' ' || *p == '\n') p++;
      char *word = p;
      while (*p && *p != ' ' && *p != '\n') p++;
      if (strncmp(word, "cpus=", 5) == 0) zr_have_cpus = zr_parse_cpus(word + 5, p);
      else if (strncmp(word, "pass=", 5) == 0)
        for (const char *d = word + 5; d < p && *d >= '0' && *d <= '9'; d++) zr_ctl_pass = zr_ctl_pass * 10 + (unsigned long)(*d - '0');
      else if (strncmp(word, "frames=", 7) == 0 && (size_t)(p - word - 7) < sizeof(zr_frames_path)) {
        memcpy(zr_frames_path, word + 7, (size_t)(p - word - 7));
        zr_frames_path[p - word - 7] = 0;
      }
    }
  }
  return c - '0';
}

static unsigned long zr_hex(const char **p) {
  unsigned long v = 0;
  for (;; (*p)++) {
    char c = **p;
    if (c >= '0' && c <= '9') v = v * 16 + (unsigned long)(c - '0');
    else if (c >= 'a' && c <= 'f') v = v * 16 + (unsigned long)(c - 'a' + 10);
    else return v;
  }
}

static int zr_eligible(const char *line, int compress, unsigned long *start, unsigned long *end) {
  const char *p = line;
  *start = zr_hex(&p);
  if (*p++ != '-') return 0;
  *end = zr_hex(&p);
  if (*p++ != ' ' || p[0] != 'r' || p[3] != 'p') return 0;
  if (!compress) return 1;
  if (p[1] != 'w') return 0;
  for (int field = 0; field < 4; field++) {
    while (*p && *p != ' ') p++;
    while (*p == ' ') p++;
  }
  return *p == 0 || strncmp(p, "[heap]", 6) == 0 || strncmp(p, "[anon:", 6) == 0;
}

static void zr_read_back(int pagemap, unsigned long start, unsigned long end) {
  pid_t self = (pid_t)zr_sys(SYS_getpid);
  struct iovec local = {zr_sink, 0};
  for (unsigned long addr = start; addr < end;) {
    unsigned long count = (end - addr) / zr_page;
    if (count > ZR_BATCH) count = ZR_BATCH;
    if (zr_sys(SYS_lseek, pagemap, (long)(addr / zr_page * sizeof(uint64_t)), SEEK_SET) < 0) return;
    long got = zr_sys(SYS_read, pagemap, zr_entries, count * sizeof(uint64_t));
    if (got < (long)sizeof(uint64_t)) return;
    unsigned long n = 0;
    for (unsigned long i = 0; i < (unsigned long)got / sizeof(uint64_t); i++) {
      if (!(zr_entries[i] & ZR_SWAPPED)) continue;
      zr_iov[n].iov_base = (void *)(addr + i * zr_page);
      zr_iov[n++].iov_len = 1;
    }
    for (unsigned long done = 0; done < n;) {
      local.iov_len = n - done;
      long r = zr_sys(SYS_process_vm_readv, self, &local, 1UL, zr_iov + done, n - done, 0UL);
      done += r > 0 ? (unsigned long)r : 1;
    }
    addr += (unsigned long)got / sizeof(uint64_t) * zr_page;
  }
}

static void zr_walk(int compress) {
  int maps = zr_open("/proc/self/maps");
  if (maps < 0) return;
  int pagemap = compress ? -1 : zr_open("/proc/self/pagemap");
  if (!compress && pagemap < 0) {
    zr_sys(SYS_close, maps);
    return;
  }
  size_t have = 0;
  for (;;) {
    long n = zr_sys(SYS_read, maps, zr_maps + have, sizeof(zr_maps) - 1 - have);
    if (n < 0 && errno == EINTR) continue;
    if (n <= 0) break;
    have += (size_t)n;
    zr_maps[have] = 0;
    char *line = zr_maps;
    char *nl;
    while ((nl = memchr(line, '\n', have - (size_t)(line - zr_maps))) != NULL) {
      *nl = 0;
      unsigned long s, e;
      if (zr_eligible(line, compress, &s, &e) && e > s) {
        if (compress) zr_sys(SYS_madvise, s, e - s, MADV_PAGEOUT);
        else zr_read_back(pagemap, s, e);
      }
      line = nl + 1;
    }
    have -= (size_t)(line - zr_maps);
    memmove(zr_maps, line, have);
    if (have == sizeof(zr_maps) - 1) have = 0;
  }
  if (pagemap >= 0) zr_sys(SYS_close, pagemap);
  zr_sys(SYS_close, maps);
}

static long zr_now_ns(void) {
  struct timespec t;
  clock_gettime(CLOCK_MONOTONIC, &t);
  return t.tv_sec * 1000000000L + t.tv_nsec;
}

// gamescope's frame ring (patch 0125): whether the game is in a bad stretch right now. Bad = a frame is
// late at this moment (none for 3x the usual frame time, under 2 s: a hitch, not a menu or a load) or one
// of the frames of the last 500 ms took over twice the usual (median of the last 64) and 8 ms more.
static int zr_read_at(int fd, void *buffer, long length, long offset) {
  if (zr_sys(SYS_lseek, fd, offset, SEEK_SET) != offset) return 0;
  return zr_sys(SYS_read, fd, buffer, length) == length;
}

static int zr_frames_bad(int fd, long now) {
  uint32_t head[6];  // magic, version, capacity, pid, frames written (u64)
  if (fd < 0 || !zr_read_at(fd, head, 24L, 0L) || head[0] != ZR_FRAMES_MAGIC) return 0;
  uint32_t capacity = head[2];
  uint64_t count;
  memcpy(&count, head + 4, 8);
  if (capacity == 0 || capacity > 65536 || count < 16) return 0;
  uint64_t look = count < ZR_FRAMES_LOOK ? count : ZR_FRAMES_LOOK;
  if (look > capacity) look = capacity;
  // The newest `look` frames, oldest first: one read, two where the ring wraps.
  uint64_t frame[ZR_FRAMES_LOOK][2];
  uint64_t first = (count - look) % capacity, part = capacity - first < look ? capacity - first : look;
  if (!zr_read_at(fd, frame, (long)(part * 16), (long)(ZR_FRAMES_HEADER + first * 16))) return 0;
  if (part < look && !zr_read_at(fd, frame[part], (long)((look - part) * 16), (long)ZR_FRAMES_HEADER)) return 0;
  long times[ZR_FRAMES_LOOK];
  for (uint64_t i = 0; i < look; i++) {
    long v = (long)frame[i][0];
    uint64_t j = i;
    for (; j > 0 && times[j - 1] > v; j--) times[j] = times[j - 1];
    times[j] = v;
  }
  long usual = times[look / 2];
  long last = (long)frame[look - 1][1];
  if (usual <= 0 || now - last >= 2000000000L) return 0;
  if (now - last > 3 * usual && now - last > usual + 8000000L) return 1;
  for (uint64_t i = look; i-- > 0 && now - (long)frame[i][1] < 500000000L;)
    if ((long)frame[i][0] > 2 * usual && (long)frame[i][0] > usual + 8000000L) return 1;
  return 0;
}

// For one slice: this thread onto the mode's cores and to SCHED_IDLE; back as it was after. SCHED_IDLE
// only when RLIMIT_NICE lets the thread leave it again (Android: 40); otherwise only the cores change.
struct zr_saved {
  int mask;
  int policy;
  unsigned long cpus[ZR_MASK_WORDS];
};

static void zr_lower(struct zr_saved *saved) {
  saved->mask = 0;
  saved->policy = -1;
  if (zr_have_cpus) {
    long n = zr_sys(SYS_sched_getaffinity, 0L, sizeof(saved->cpus), saved->cpus);
    if (n > 0) {
      unsigned long want[ZR_MASK_WORDS];
      int any = 0, same = 1;
      for (int i = 0; i < ZR_MASK_WORDS; i++) {
        want[i] = (size_t)i * sizeof(unsigned long) < (size_t)n ? saved->cpus[i] & zr_cpus[i] : 0;
        any |= want[i] != 0;
        if ((size_t)i * sizeof(unsigned long) < (size_t)n) same &= want[i] == saved->cpus[i];
      }
      if (any && !same && zr_sys(SYS_sched_setaffinity, 0L, (long)n, want) == 0) saved->mask = (int)n;
    }
  }
  long policy = zr_sys(SYS_sched_getscheduler, 0L);
  if (policy < 0 || ((policy & ~SCHED_RESET_ON_FORK) != SCHED_OTHER && (policy & ~SCHED_RESET_ON_FORK) != SCHED_BATCH)) return;
  long prio = zr_sys(SYS_getpriority, (long)PRIO_PROCESS, 0L);  // the raw call gives 20 - nice
  uint64_t limit[2];  // the kernel's struct rlimit64 on every ABI: current, maximum
  if (prio < 1 || zr_sys(SYS_prlimit64, 0L, (long)RLIMIT_NICE, NULL, limit) != 0) return;
  if (limit[0] != ~0ULL && limit[0] < (uint64_t)prio) return;
  struct sched_param param = {0};
  if (zr_sys(SYS_sched_setscheduler, 0L, (long)SCHED_IDLE, &param) == 0) saved->policy = (int)policy;
}

static void zr_restore(const struct zr_saved *saved) {
  if (saved->policy >= 0) {
    struct sched_param param = {0};
    zr_sys(SYS_sched_setscheduler, 0L, (long)saved->policy, &param);
  }
  if (saved->mask > 0) zr_sys(SYS_sched_setaffinity, 0L, (long)saved->mask, saved->cpus);
}

// One slice of a mode-2 page-out, from zr_cursor on; 1 when the walk reached the end.
static int zr_gentle(void) {
  long start = zr_now_ns();
  struct zr_saved saved;
  zr_lower(&saved);
  int frames = zr_frames_path[0] ? zr_open(zr_frames_path) : -1;
  int maps = zr_open("/proc/self/maps");
  int done = maps >= 0, stop = 0;
  size_t have = 0;
  zr_slices++;
  while (maps >= 0 && !stop) {
    long n = zr_sys(SYS_read, maps, zr_maps + have, sizeof(zr_maps) - 1 - have);
    if (n < 0 && errno == EINTR) continue;
    if (n <= 0) break;
    have += (size_t)n;
    zr_maps[have] = 0;
    char *line = zr_maps;
    char *nl;
    while (!stop && (nl = memchr(line, '\n', have - (size_t)(line - zr_maps))) != NULL) {
      *nl = 0;
      unsigned long s, e;
      if (zr_eligible(line, 1, &s, &e) && e > zr_cursor) {
        for (unsigned long addr = s > zr_cursor ? s : zr_cursor; addr < e;) {
          long now = zr_now_ns();
          if (now - start >= ZR_SLICE_NS) {
            stop = 1;
            break;
          }
          if (zr_frames_bad(frames, now)) {
            zr_paused++;
            stop = 1;
            break;
          }
          unsigned long len = e - addr < ZR_STEP ? e - addr : ZR_STEP;
          zr_sys(SYS_madvise, addr, len, MADV_PAGEOUT);
          addr += len;
          zr_cursor = addr;
          zr_sys(SYS_sched_yield);
        }
      }
      line = nl + 1;
    }
    have -= (size_t)(line - zr_maps);
    memmove(zr_maps, line, have);
    if (have == sizeof(zr_maps) - 1) have = 0;
  }
  if (stop) done = 0;
  if (maps >= 0) zr_sys(SYS_close, maps);
  if (frames >= 0) zr_sys(SYS_close, frames);
  zr_restore(&saved);
  zr_busy_ms += (zr_now_ns() - start) / 1000000L;
  if (done) zr_cursor = 0;
  return done;
}

// Before it stops a session for suspend, the app waits for <ZR_CTL>.ack.<pid> to appear (the handler
// runs here, not one a program installed over it) and then to hold a byte (the page-out finished).
static void zr_ack(int done) {
  char stats[64];
  size_t length = 0;
  if (done == 2) {
    // A mode-2 pass: "1 <slices> <slices ended for the game's frames> <ms in slices>".
    long values[3] = {zr_slices, zr_paused, zr_busy_ms};
    stats[length++] = '1';
    for (int v = 0; v < 3; v++) {
      char digits[20];
      size_t d = 0;
      long value = values[v] < 0 ? 0 : values[v];
      do digits[d++] = (char)('0' + value % 10); while ((value /= 10) > 0);
      stats[length++] = ' ';
      while (d > 0) stats[length++] = digits[--d];
    }
  }
  static const char suffix[] = ".ack.";
  char path[sizeof(ZR_CTL) + sizeof(suffix) + 20];
  char digits[20];
  size_t n = 0, d = 0;
  for (const char *p = ZR_CTL; *p; p++) path[n++] = *p;
  for (const char *p = suffix; *p; p++) path[n++] = *p;
  long pid = zr_sys(SYS_getpid);
  do digits[d++] = (char)('0' + pid % 10); while ((pid /= 10) > 0);
  while (d > 0) path[n++] = digits[--d];
  path[n] = 0;
  int fd = (int)zr_sys(SYS_openat, AT_FDCWD, path, O_WRONLY | O_CREAT | O_TRUNC | O_CLOEXEC, 0600);
  if (fd < 0) return;
  if (done == 2) zr_sys(SYS_write, fd, stats, (unsigned long)length);
  else if (done) zr_sys(SYS_write, fd, "1", 1UL);
  zr_sys(SYS_close, fd);
}

static void zr_handler(int sig, siginfo_t *info, void *context) {
  (void)sig;
  (void)context;
  if (info == NULL || info->si_code != SI_USER) return;
  int saved = errno;
  int sliced = 0;
  __atomic_store_n(&zr_pending, 1, __ATOMIC_SEQ_CST);
  while (__atomic_load_n(&zr_pending, __ATOMIC_SEQ_CST)
         && !__atomic_exchange_n(&zr_busy, 1, __ATOMIC_SEQ_CST)) {
    __atomic_store_n(&zr_pending, 0, __ATOMIC_SEQ_CST);
    int mode = zr_mode();
    // One slice per signal: a mode-2 signal that came in meanwhile waits for the session's next one.
    if (mode == 2 && sliced) {
      __atomic_store_n(&zr_busy, 0, __ATOMIC_SEQ_CST);
      break;
    }
    if (mode == 2) {
      sliced = 1;
      // A new pass number starts over; a signal for a pass that is done (one queued while its last
      // slice ran) does nothing.
      if (zr_pass_state == 0 || zr_ctl_pass != zr_pass) {
        zr_pass = zr_ctl_pass;
        zr_pass_state = 1;
        zr_cursor = 0;
        zr_slices = zr_paused = zr_busy_ms = 0;
        zr_ack(0);
      }
      if (zr_pass_state == 1 && zr_gentle()) {
        zr_pass_state = 2;
        zr_ack(2);
      }
    } else {
      zr_cursor = 0;
      zr_pass_state = 0;
      if (mode == 1) zr_ack(0);
      if (mode >= 0) zr_walk(mode);
      if (mode == 1) zr_ack(1);
    }
    __atomic_store_n(&zr_busy, 0, __ATOMIC_SEQ_CST);
  }
  errno = saved;
}

static void zr_child(void) {
  zr_pass_state = 0;
  zr_cursor = 0;
  __atomic_store_n(&zr_busy, 0, __ATOMIC_SEQ_CST);
  __atomic_store_n(&zr_pending, 0, __ATOMIC_SEQ_CST);
}

__attribute__((constructor)) static void zr_init(void) {
  void *libc = dlopen("libc.so.6", RTLD_LAZY | RTLD_NOLOAD);
  if (libc == NULL) return;
  zr_sys = (long (*)(long, ...))dlsym(libc, "syscall");
  if (zr_sys == NULL) return;
  int fd = zr_open(ZR_CTL);
  if (fd < 0) return;
  zr_sys(SYS_close, fd);
  long page = sysconf(_SC_PAGESIZE);
  zr_page = page > 0 ? (unsigned long)page : 4096UL;
  struct sigaction old;
  if (sigaction(SIGURG, NULL, &old) != 0) return;
  if ((old.sa_flags & SA_SIGINFO) || old.sa_handler != SIG_DFL) return;
  struct sigaction sa;
  memset(&sa, 0, sizeof(sa));
  sa.sa_sigaction = zr_handler;
  sa.sa_flags = SA_SIGINFO | SA_RESTART;
  sigemptyset(&sa.sa_mask);
  if (sigaction(SIGURG, &sa, NULL) == 0) pthread_atfork(NULL, NULL, zr_child);
}
