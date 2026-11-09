#!/usr/bin/env bash
# Host check (x86_64 Linux 4.8+, gcc): builds the patched proot for the host and runs a small probe under it while a
# seccomp filter shaped like Android 10's app policy is in force: syscalls newer than that policy (statx, rseq, clone3,
# faccessat2, ...) and the out-of-range void number PRoot writes at its ptrace stops raise SIGSYS, as bionic's
# SECCOMP_RET_TRAP does. It runs with PRoot's own filter on and with PROOT_NO_SECCOMP=1, and fails if a call PRoot
# emulates or refuses comes back as ENOSYS (getcwd, chdir and new files "Function not implemented").
# Usage: tools/proot/bench/android10-filter-check.sh [patch-dir]   (defaults to tools/proot/patches)
set -euo pipefail
HERE=$(cd "$(dirname "$0")/.." && pwd)
PATCHES=${1:-$HERE/patches}
. "$HERE/source.env"
[[ $(uname -m) == x86_64 ]] || { echo "x86_64 host only" >&2; exit 2; }
WORK=$(mktemp -d)
trap 'rm -rf "$WORK"' EXIT
fetch() {
  curl -fsSL --retry 3 -o "$WORK/$1" "$2"
  printf '%s  %s\n' "$3" "$WORK/$1" | sha256sum -c - >/dev/null
  mkdir -p "$WORK/${1%%.*}"
  tar -xzf "$WORK/$1" -C "$WORK/${1%%.*}" --strip-components=1
}
fetch proot.tar.gz "https://github.com/termux/proot/archive/${PROOT_COMMIT}.tar.gz" "$PROOT_SHA256"
fetch talloc.tar.gz "https://www.samba.org/ftp/talloc/talloc-${TALLOC_VERSION}.tar.gz" "$TALLOC_SHA256"
cp "$HERE/statx_mnt_id.h" "$WORK/proot/src/tracee/statx_mnt_id.h"
cp "$HERE/profile.h" "$WORK/proot/src/syscall/profile.h"
for patch in "$PATCHES"/*.patch; do patch -d "$WORK/proot" -p1 --forward --quiet < "$patch"; done

# Host-only stand-ins for what the NDK provides: ld.bfd has no --rosegment, glibc has no <linux/ashmem.h> and no
# struct termios2 beside <termios.h>, and glibc's __O_TMPFILE includes O_DIRECTORY where bionic's does not.
sed -i 's/,--rosegment,/,/' "$WORK/proot/src/GNUmakefile"
sed -i 's|^#include <termios.h>.*|&\nstruct termios2 { unsigned int c_iflag, c_oflag, c_cflag, c_lflag; unsigned char c_line; unsigned char c_cc[19]; unsigned int c_ispeed, c_ospeed; };|' \
  "$WORK/proot/src/syscall/enter.c"
mkdir -p "$WORK/inc/linux"
cat > "$WORK/inc/shim.h" <<'EOF'
#ifndef __ASSEMBLER__
#define __O_TMPFILE 020000000
#include <asm/ioctls.h>
#endif
EOF
cat > "$WORK/inc/linux/ashmem.h" <<'EOF'
#include <linux/ioctl.h>
#define ASHMEM_NAME_LEN 256
#define ASHMEM_SET_NAME _IOW(0x77, 1, char[ASHMEM_NAME_LEN])
#define ASHMEM_GET_NAME _IOR(0x77, 2, char[ASHMEM_NAME_LEN])
#define ASHMEM_SET_SIZE _IOW(0x77, 3, size_t)
#define ASHMEM_GET_SIZE _IO(0x77, 4)
EOF
mkdir -p "$WORK/talloc/config"
cp "$HERE/talloc-config.h" "$WORK/talloc/config/config.h"
gcc -c -O2 -fPIC -include limits.h -D__STDC_WANT_LIB_EXT1__=1 -DHAVE_CONFIG_H -I"$WORK/talloc/config" -I"$WORK/talloc" \
  -I"$WORK/talloc/lib/replace" -o "$WORK/talloc.o" "$WORK/talloc/talloc.c"
ar rcs "$WORK/libtalloc.a" "$WORK/talloc.o"
make -s -C "$WORK/proot/src" -j"$(nproc)" CC=gcc \
  CPPFLAGS="-D_FILE_OFFSET_BITS=64 -D_GNU_SOURCE -D__ANDROID__ -include $WORK/inc/shim.h -I. -I$WORK/talloc -I$WORK/inc -DARG_MAX=131072 -w" \
  LDFLAGS="$WORK/libtalloc.a -Wl,-z,noexecstack" proot loader >"$WORK/make.log" 2>&1 || { tail -20 "$WORK/make.log"; exit 1; }

cat > "$WORK/filter.c" <<'EOF'
#include <linux/audit.h>
#include <linux/filter.h>
#include <linux/seccomp.h>
#include <stddef.h>
#include <stdio.h>
#include <sys/prctl.h>
#include <unistd.h>
int main(int argc, char **argv) {
  struct sock_filter f[] = {
    BPF_STMT(BPF_LD | BPF_W | BPF_ABS, offsetof(struct seccomp_data, arch)),
    BPF_JUMP(BPF_JMP | BPF_JEQ | BPF_K, AUDIT_ARCH_X86_64, 1, 0),
    BPF_STMT(BPF_RET | BPF_K, SECCOMP_RET_ALLOW),
    BPF_STMT(BPF_LD | BPF_W | BPF_ABS, offsetof(struct seccomp_data, nr)),
    BPF_JUMP(BPF_JMP | BPF_JGE | BPF_K, 332 /* statx and newer, and -1/-2 */, 0, 1),
    BPF_STMT(BPF_RET | BPF_K, SECCOMP_RET_TRAP),
    BPF_STMT(BPF_RET | BPF_K, SECCOMP_RET_ALLOW),
  };
  struct sock_fprog prog = { sizeof f / sizeof f[0], f };
  if (argc < 2 || prctl(PR_SET_NO_NEW_PRIVS, 1, 0, 0, 0) || prctl(PR_SET_SECCOMP, SECCOMP_MODE_FILTER, &prog)) return 2;
  execvp(argv[1], argv + 1);
  return 127;
}
EOF
cat > "$WORK/probe.c" <<'EOF'
#define _GNU_SOURCE
#include <errno.h>
#include <fcntl.h>
#include <stdio.h>
#include <string.h>
#include <sys/stat.h>
#include <sys/syscall.h>
#include <unistd.h>
static int bad;
static void expect(const char *what, long r, int want_errno) {
  int e = r < 0 ? errno : 0;
  if (e != want_errno) { printf("FAIL %s: %s (wanted %s)\n", what, e ? strerror(e) : "ok", want_errno ? strerror(want_errno) : "ok"); bad = 1; }
}
int main(int argc, char **argv) {
  char buf[4096];
  struct statx stx;
  const char *dir = argv[1];
  expect("getcwd", getcwd(buf, sizeof buf) ? 0 : -1, 0);
  expect("chdir", chdir(dir), 0);
  if (!getcwd(buf, sizeof buf)) expect("getcwd after chdir", -1, 0);
  else if (strcmp(buf, dir)) { printf("FAIL getcwd after chdir: %s\n", buf); bad = 1; }
  if (argc > 2) { /* statx is refused by the filter and emulated by PRoot from the SIGSYS */
    expect("statx existing dir", syscall(SYS_statx, AT_FDCWD, dir, 0, STATX_BASIC_STATS, &stx), 0);
    expect("statx missing", syscall(SYS_statx, AT_FDCWD, "missing/x", 0, STATX_BASIC_STATS, &stx), ENOENT);
  }
  expect("open in a missing directory", open("missing/x", O_WRONLY | O_CREAT, 0644), ENOENT);
  expect("mkdir in a missing directory", mkdir("missing/x", 0755), ENOENT);
  expect("mkdir existing", mkdir(dir, 0755), EEXIST);
  return bad;
}
EOF
gcc -O2 -o "$WORK/filter" "$WORK/filter.c"
gcc -O2 -o "$WORK/probe" "$WORK/probe.c"
mkdir -p "$WORK/tmp" "$WORK/dir"
status=0
for mode in "seccomp on" "PROOT_NO_SECCOMP=1"; do
  # statx is checked with PRoot's filter on only: on x86_64 a refused statx reaches PRoot's SIGSYS handler after its
  # ptrace exit stop has put the result in rax, where the handler reads the syscall number (aarch64 keeps it in x8).
  extra=(); statx=(statx); [[ $mode == PROOT_NO_SECCOMP=1 ]] && extra=(PROOT_NO_SECCOMP=1) && statx=()
  # glibc's rseq registration is turned off for the host proot only; bionic's proot never calls rseq.
  if out=$(env "${extra[@]}" PROOT_TMP_DIR="$WORK/tmp" PROOT_LOADER="$WORK/proot/src/loader/loader" GLIBC_TUNABLES=glibc.pthread.rseq=0 \
      "$WORK/filter" "$WORK/proot/src/proot" -r / -w "$WORK" /usr/bin/env -u GLIBC_TUNABLES "$WORK/probe" "$WORK/dir" "${statx[@]}" 2>&1); then
    echo "ok   $mode"
  else
    echo "FAIL $mode"; echo "$out" | sed 's/^/     /'; status=1
  fi
done
exit $status
