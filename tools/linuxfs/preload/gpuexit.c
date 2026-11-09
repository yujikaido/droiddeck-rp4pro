/* Steam's browser GPU process: who ends it.
 *
 * On vivo phones (X100 Pro, X200, X300, X300 Pro: G720, G925 and G1-Ultra alike) Chromium's GPU
 * process (steamwebhelper --type=gpu-process) exits with status 1 about 10-50 ms after it starts,
 * six times, and Steam turns its GPU off (webhelper.txt "Disabling GPU acceleration:
 * Disabled/CrashCount"), so its menus draw in software at 10-15 fps. The process writes nothing
 * first, not even with Chromium's log on, and never reaches the Vulkan bridge, so the logs cannot
 * say what ends it. Here, in that process only:
 *   - at start: one line saying the process got this far (the preload loaded, the dynamic linker
 *     is done), with its pid;
 *   - on exit() / _exit() / _Exit() with a status other than 0: the status, the library and offset
 *     of the caller (and its symbol when it is exported), errno, and the time since start.
 * No line at start means it ends before libraries' constructors run (in the exec or the linker).
 * The lines go to Steam's log folder (droiddeck_webhelper_gpu.txt, collected with the other
 * Steam logs; started afresh past 256 KB) and to stderr. Every other process: nothing but the
 * one look at /proc/self/cmdline. Only in sessions on the Mali bridge (BL_VKBRIDGE=1);
 * BL_WEBHELPER_GPU_DIAG=0 turns it off, =1 on in any session. */
#define _GNU_SOURCE
#include <dlfcn.h>
#include <errno.h>
#include <fcntl.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/stat.h>
#include <sys/syscall.h>
#include <time.h>
#include <unistd.h>

#define GPU_DIAG_MAX_BYTES (256 * 1024)

static int gpu_diag_on;
static int gpu_diag_fd = -1;
/* The real functions, looked up in the constructor in every process: never in the exit path,
 * which may run in a vfork() child sharing its parent's memory */
static void (*real_exit)(int), (*real__exit)(int), (*real__Exit)(int);
static struct timespec gpu_diag_t0;

/* Whether this process is steamwebhelper --type=gpu-process, from /proc/self/cmdline. Exposed
 * (hidden) for the host test. */
__attribute__((visibility("hidden"))) int bl_gpuexit_is_gpu_process(const char *cmdline, size_t len) {
  if (!cmdline || !len) return 0;
  const char *argv0 = cmdline;
  const char *base = strrchr(argv0, '/');
  base = base ? base + 1 : argv0;
  if (strcmp(base, "steamwebhelper")) return 0;
  for (size_t i = strlen(argv0) + 1; i < len;) {
    const char *arg = cmdline + i;
    if (!strcmp(arg, "--type=gpu-process")) return 1;
    i += strlen(arg) + 1;
  }
  return 0;
}

static void gpu_diag_write(const char *line, size_t n) {
  if (gpu_diag_fd >= 0 && write(gpu_diag_fd, line, n) < 0) { /* nothing more to do */ }
  if (write(2, line, n) < 0) { /* nothing more to do */ }
}

static double gpu_diag_ms(void) {
  struct timespec t;
  clock_gettime(CLOCK_MONOTONIC, &t);
  return (t.tv_sec - gpu_diag_t0.tv_sec) * 1e3 + (t.tv_nsec - gpu_diag_t0.tv_nsec) / 1e6;
}

static void gpu_diag_open(void) {
  char path[512];
  const char *data = getenv("XDG_DATA_HOME");
  const char *home = getenv("HOME");
  if (data && *data) snprintf(path, sizeof(path), "%s/Steam/logs/droiddeck_webhelper_gpu.txt", data);
  else snprintf(path, sizeof(path), "%s/.local/share/Steam/logs/droiddeck_webhelper_gpu.txt", home && *home ? home : "/root");
  struct stat st;
  int flags = O_WRONLY | O_CREAT | O_APPEND | O_CLOEXEC;
  if (!stat(path, &st) && st.st_size > GPU_DIAG_MAX_BYTES) flags |= O_TRUNC;
  gpu_diag_fd = open(path, flags, 0644);
}

__attribute__((constructor)) static void gpu_diag_init(void) {
  real_exit = (void (*)(int))dlsym(RTLD_NEXT, "exit");
  real__exit = (void (*)(int))dlsym(RTLD_NEXT, "_exit");
  real__Exit = (void (*)(int))dlsym(RTLD_NEXT, "_Exit");
  /* Only on the Mali bridge (BL_VKBRIDGE=1); BL_WEBHELPER_GPU_DIAG=1 turns it on anywhere, =0 off */
  const char *off = getenv("BL_WEBHELPER_GPU_DIAG");
  if (off && !strcmp(off, "0")) return;
  const char *bridge = getenv("BL_VKBRIDGE");
  if (!(off && !strcmp(off, "1")) && !(bridge && !strcmp(bridge, "1"))) return;
  char buf[8192];
  int fd = open("/proc/self/cmdline", O_RDONLY | O_CLOEXEC);
  if (fd < 0) return;
  ssize_t n = read(fd, buf, sizeof(buf) - 1);
  close(fd);
  if (n <= 0) return;
  buf[n] = 0;
  if (!bl_gpuexit_is_gpu_process(buf, (size_t)n)) return;
  clock_gettime(CLOCK_MONOTONIC, &gpu_diag_t0);
  gpu_diag_open();
  gpu_diag_on = 1;
  char line[160];
  time_t now = time(NULL);
  struct tm tm;
  localtime_r(&now, &tm);
  int m = snprintf(line, sizeof(line), "%04d-%02d-%02d %02d:%02d:%02d droiddeck: steamwebhelper GPU process %d started (preload loaded)\n",
                   tm.tm_year + 1900, tm.tm_mon + 1, tm.tm_mday, tm.tm_hour, tm.tm_min, tm.tm_sec, (int)getpid());
  if (m > 0) gpu_diag_write(line, (size_t)m < sizeof(line) ? (size_t)m : sizeof(line) - 1);
}

/* The line for a non-zero exit; formatted into out (exposed, hidden, for the host test) */
__attribute__((visibility("hidden"))) int bl_gpuexit_format(char *out, size_t size, const char *how, int status, const void *caller,
                                                            int err, double ms) {
  Dl_info info;
  memset(&info, 0, sizeof(info));
  const char *lib = "?", *sym = NULL;
  unsigned long off = (unsigned long)caller;
  if (caller && dladdr(caller, &info) && info.dli_fname) {
    lib = strrchr(info.dli_fname, '/') ? strrchr(info.dli_fname, '/') + 1 : info.dli_fname;
    off = (unsigned long)((const char *)caller - (const char *)info.dli_fbase);
    sym = info.dli_sname;
  }
  return snprintf(out, size, "droiddeck: steamwebhelper GPU process %d %s(%d) called from %s+0x%lx%s%s%s, errno %d (%s), %.1f ms after start\n",
                  (int)getpid(), how, status, lib, off, sym ? " (" : "", sym ? sym : "", sym ? ")" : "", err, strerror(err), ms);
}

/* childkill.c: the same for every other steamwebhelper child with a non-zero status */
void bl_webhelper_exit_note(const char *how, int status, const void *caller, int err) __attribute__((weak, visibility("hidden")));

static void gpu_diag_exit(const char *how, int status, const void *caller, int err) {
  if (!gpu_diag_on || !status) return;
  gpu_diag_on = 0; /* once: exit() may end in _exit() */
  char line[512];
  int m = bl_gpuexit_format(line, sizeof(line), how, status, caller, err, gpu_diag_ms());
  if (m > 0) gpu_diag_write(line, (size_t)m < sizeof(line) ? (size_t)m : sizeof(line) - 1);
}

void exit(int status) {
  int err = errno;
  gpu_diag_exit("exit", status, __builtin_return_address(0), err);
  if (bl_webhelper_exit_note) bl_webhelper_exit_note("exit", status, __builtin_return_address(0), err);
  /* before the constructor (another library's constructor exiting): looked up now, so atexit
   * handlers and stdio still run; exit() in a vfork() child is wrong in any case */
  if (!real_exit) real_exit = (void (*)(int))dlsym(RTLD_NEXT, "exit");
  if (real_exit) real_exit(status);
  syscall(SYS_exit_group, status);
  __builtin_unreachable();
}

void _exit(int status) {
  int err = errno;
  gpu_diag_exit("_exit", status, __builtin_return_address(0), err);
  if (bl_webhelper_exit_note) bl_webhelper_exit_note("_exit", status, __builtin_return_address(0), err);
  if (real__exit) real__exit(status);
  syscall(SYS_exit_group, status);
  __builtin_unreachable();
}

void _Exit(int status) {
  int err = errno;
  gpu_diag_exit("_Exit", status, __builtin_return_address(0), err);
  if (bl_webhelper_exit_note) bl_webhelper_exit_note("_Exit", status, __builtin_return_address(0), err);
  if (real__Exit) real__Exit(status);
  syscall(SYS_exit_group, status);
  __builtin_unreachable();
}
