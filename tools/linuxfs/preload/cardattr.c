/*
 * A card library reached natively (/mnt/media_rw, exFAT/FAT/NTFS) is mounted by vold with uid=1023 and fixed
 * fmask/dmask: every file belongs to media_rw, never to the app, and the filesystem cannot keep modes anyway. So
 * setting a file's times or mode is refused with EPERM even though the app may write the file. Steam's "Move
 * install folder" copies a file, then utime()s it to the source's times, and on EPERM deletes the copy and
 * cancels the whole move ("Disk write failure at 1082127/26220633955 bytes, 0 files moved", Tales of Vesperia).
 * Report success there, like the kernel's own "quiet" mount option does for chmod/chown on FAT.
 */

#define _GNU_SOURCE
#include <dlfcn.h>
#include <errno.h>
#include <fcntl.h>
#include <stddef.h>
#include <sys/stat.h>
#include <sys/time.h>
#include <sys/vfs.h>
#include <utime.h>

#define EXFAT_MAGIC 0x2011BAB0
#define MSDOS_MAGIC 0x4d44
#define NTFS3_MAGIC 0x7366746e
#define NTFS_MAGIC 0x5346544e

static int card_type(unsigned long type) {
  return type == EXFAT_MAGIC || type == MSDOS_MAGIC || type == NTFS3_MAGIC || type == NTFS_MAGIC;
}

static int card_path(int dirfd, const char *path) {
  struct statfs fs;

  if (path == NULL || (path[0] != '/' && dirfd != AT_FDCWD)) return fstatfs(dirfd, &fs) == 0 && card_type(fs.f_type);
  return statfs(path, &fs) == 0 && card_type(fs.f_type);
}

static int card_fd(int fd) {
  struct statfs fs;

  return fstatfs(fd, &fs) == 0 && card_type(fs.f_type);
}

/* Only EPERM on a card becomes success; anything else, or a path that is not on a card, keeps its error. */
#define REFUSED_ON_CARD(result, on_card) \
  do { \
    if ((result) == 0 || errno != EPERM) return (result); \
    if (on_card) return 0; \
    errno = EPERM; \
    return (result); \
  } while (0)

#define NEXT(name) \
  static __typeof__(name) *next_##name; \
  if (next_##name == NULL) next_##name = (__typeof__(name) *) dlsym(RTLD_NEXT, #name); \
  if (next_##name == NULL) { errno = ENOSYS; return -1; }

int utimensat(int dirfd, const char *path, const struct timespec times[2], int flags) {
  NEXT(utimensat);
  int result = next_utimensat(dirfd, path, times, flags);
  REFUSED_ON_CARD(result, path == NULL ? card_fd(dirfd) : card_path(dirfd, path));
}

int futimens(int fd, const struct timespec times[2]) {
  NEXT(futimens);
  int result = next_futimens(fd, times);
  REFUSED_ON_CARD(result, card_fd(fd));
}

int utime(const char *path, const struct utimbuf *times) {
  NEXT(utime);
  int result = next_utime(path, times);
  REFUSED_ON_CARD(result, card_path(AT_FDCWD, path));
}

int utimes(const char *path, const struct timeval times[2]) {
  NEXT(utimes);
  int result = next_utimes(path, times);
  REFUSED_ON_CARD(result, card_path(AT_FDCWD, path));
}

int lutimes(const char *path, const struct timeval times[2]) {
  NEXT(lutimes);
  int result = next_lutimes(path, times);
  REFUSED_ON_CARD(result, card_path(AT_FDCWD, path));
}

int futimes(int fd, const struct timeval times[2]) {
  NEXT(futimes);
  int result = next_futimes(fd, times);
  REFUSED_ON_CARD(result, card_fd(fd));
}

int futimesat(int dirfd, const char *path, const struct timeval times[2]) {
  NEXT(futimesat);
  int result = next_futimesat(dirfd, path, times);
  REFUSED_ON_CARD(result, card_path(dirfd, path));
}

int chmod(const char *path, mode_t mode) {
  NEXT(chmod);
  int result = next_chmod(path, mode);
  REFUSED_ON_CARD(result, card_path(AT_FDCWD, path));
}

int fchmod(int fd, mode_t mode) {
  NEXT(fchmod);
  int result = next_fchmod(fd, mode);
  REFUSED_ON_CARD(result, card_fd(fd));
}

int fchmodat(int dirfd, const char *path, mode_t mode, int flags) {
  NEXT(fchmodat);
  int result = next_fchmodat(dirfd, path, mode, flags);
  REFUSED_ON_CARD(result, card_path(dirfd, path));
}
