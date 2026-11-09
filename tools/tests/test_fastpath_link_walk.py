"""The fast path's cost per directory entry of a Proton prefix (B9: Steam's CEG step "Updating executable").

Proton's prefix has its builtin DLLs in system32/syswow64 as ABSOLUTE symlinks into the Proton install
(proton: pfx_copy/update_builtin_libs). Wine lists a directory with, per entry, lstat(name), stat(name)
for a link, stat(name/..) for a directory and getxattr(name, ...) twice, after fchdir() into it. CEG's
helper (steamservice.exe /runfunc) lists C:\\windows\\system32 hundreds of times, so the kernel calls the
fast path makes per entry decide whether it finishes inside Steam's ~105 s limit.

The program below runs that sequence through fastpath.c in-process (no proot: the kernel cwd is the host
directory, as patch 0014 keeps it) and counts the fast path's own kernel calls. The answers must be the
ones proot gives (the guest view of the tree), and a followed link must not cost a walk of every component
of its target.
"""

import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest

FASTPATH = Path(__file__).resolve().parents[1] / "proot/fastpath/fastpath.c"

PROGRAM = r"""
#include "FASTPATH"

static long calls;
static long host_sys(long nr, long a, long b, long c, long d, long e, long f) {
  calls++;
  long r = syscall(nr, a, b, c, d, e, f);
  return r < 0 ? -errno : r;
}

static void show(const char *what, const char *name, int r, const struct stat *st) {
  if (r < 0) printf("%s %s err %d\n", what, name, errno);
  else printf("%s %s mode %o ino %lu size %ld\n", what, name, (unsigned)st->st_mode, (unsigned long)st->st_ino, (long)st->st_size);
}

int main(int argc, char **argv) {
  static char hosts[MAX_BINDS][PATH_MAX], guests[MAX_BINDS][512];
  fp_sys = host_sys;
  snprintf(root, sizeof root, "%s", argv[1]);
  rootlen = strlen(root);
  if (argv[2][0] == '0') ttl_ns = 0;
  if (chdir(argv[3]) != 0) return 2;
  int i = 4;
  for (; i + 1 < argc && strcmp(argv[i], "--"); i += 2) {
    struct bind *bd = &binds[nbinds];
    bd->host = hosts[nbinds];
    bd->guest = guests[nbinds];
    snprintf(bd->host, PATH_MAX, "%s", argv[i]);
    snprintf(bd->guest, sizeof guests[0], "%s", argv[i + 1]);
    bd->hlen = strlen(bd->host);
    bd->glen = strlen(bd->guest);
    nbinds++;
  }
  pthread_once(&init_once, fp_init);
  fp_on = 1;
  long before_misses = misses;
  for (int round = 0; round < 2; round++) {
    long link_calls = 0, links = 0, start = calls;
    for (int k = i + 1; k < argc; k++) {
      const char *name = argv[k];
      char up[PATH_MAX], value[64];
      struct stat st, target;
      int r = lstat(name, &st);
      if (round) show("lstat", name, r, &st);
      if (r == 0 && S_ISLNK(st.st_mode)) {
        long c0 = calls;
        int s = stat(name, &target);
        ssize_t x1 = getxattr(name, "user.WINEREPARSE", value, sizeof value);
        int e1 = x1 < 0 ? errno : 0;
        ssize_t x2 = getxattr(name, "user.DOSATTRIB", value, sizeof value);
        int e2 = x2 < 0 ? errno : 0;
        link_calls += calls - c0;
        links++;
        if (round) {
          show("stat", name, s, &target);
          printf("xattr %s %d %d\n", name, e1, e2);
        }
      } else if (r == 0) {
        if (S_ISDIR(st.st_mode)) {
          snprintf(up, sizeof up, "%s/..", name);
          int u = stat(up, &target);
          if (round) show("up", name, u, &target);
        }
        ssize_t x = getxattr(name, "user.DOSATTRIB", value, sizeof value);
        if (round) printf("xattr %s %d\n", name, x < 0 ? errno : 0);
      }
    }
    if (round) printf("calls %ld links %ld link_calls %ld misses %ld\n", calls - start, links, link_calls, misses - before_misses);
  }
  return 0;
}
"""

PROTON = "/root/.local/share/Steam/steamapps/common/Proton - Experimental/files/lib/wine/aarch64-windows"
SD_PROTON = "/mnt/droiddeck-sd/steamapps/common/Proton - Experimental/files/lib/wine/i386-windows"
SYSTEM32 = "/root/.local/share/Steam/steamapps/compatdata/8190/pfx/drive_c/windows/system32"
LINKS = 40


class FastPathLinkWalkTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        if shutil.which("gcc") is None:
            raise unittest.SkipTest("gcc is not available")
        cls.work = Path(tempfile.mkdtemp())
        source = cls.work / "walk.c"
        source.write_text(PROGRAM.replace("FASTPATH", str(FASTPATH)))
        cls.program = cls.work / "walk"
        subprocess.run(["gcc", "-O1", "-w", "-pthread", "-o", str(cls.program), str(source), "-ldl"], check=True)
        t = cls.work / "tree"
        root = t / "root"
        sd = t / "sd"
        cls.root, cls.sd = root, sd
        for d in [root / PROTON.lstrip("/"), root / SYSTEM32.lstrip("/") / "drivers", root / "mnt/droiddeck-sd",
                  sd / SD_PROTON[len("/mnt/droiddeck-sd/"):], root / "opt/real/dir"]:
            d.mkdir(parents=True, exist_ok=True)
        sys32 = root / SYSTEM32.lstrip("/")
        cls.names = []
        for n in range(LINKS):
            name = "lib%02d.dll" % n
            (root / PROTON.lstrip("/") / name).write_bytes(b"x" * (n + 1))
            os.symlink(PROTON + "/" + name, sys32 / name)
            cls.names.append(name)
        (sd / SD_PROTON[len("/mnt/droiddeck-sd/"):] / "sd.dll").write_bytes(b"sdsd")
        os.symlink(SD_PROTON + "/sd.dll", sys32 / "sd.dll")
        (sys32 / "plain.txt").write_bytes(b"plain")
        os.symlink("plain.txt", sys32 / "rel.txt")
        os.symlink(PROTON + "/missing.dll", sys32 / "dangling.dll")
        os.symlink("/opt/link/dir", sys32 / "twohop")
        os.symlink("/opt/real", root / "opt/link")
        os.symlink(PROTON + "/../aarch64-windows/lib00.dll", sys32 / "dotdot.dll")
        os.symlink(PROTON, sys32 / "protondir")
        os.symlink("/opt/./real//dir", sys32 / "dotted")
        cls.extra = ["sd.dll", "plain.txt", "rel.txt", "dangling.dll", "twohop", "dotdot.dll", "protondir",
                     "dotted", "drivers"]
        cls.binds = [(sd, "/mnt/droiddeck-sd"), (sd, "/mnt/bannerlator-sd")]

    @classmethod
    def tearDownClass(cls):
        shutil.rmtree(cls.work, ignore_errors=True)

    def run_walk(self, ttl, names):
        argv = [str(self.program), str(self.root), ttl, str(self.root / SYSTEM32.lstrip("/"))]
        for host, guest in self.binds:
            argv += [str(host), guest]
        argv.append("--")
        argv += names
        out = subprocess.run(argv, capture_output=True, text=True, timeout=30, check=True).stdout
        lines = out.splitlines()
        tail = lines[-1].split()
        counts = dict(zip(tail[0::2], (int(v) for v in tail[1::2])))
        return lines[:-1], counts

    def guest_stat(self, guest):
        if guest.startswith("/mnt/droiddeck-sd/"):
            return os.stat(self.sd / guest[len("/mnt/droiddeck-sd/"):])
        return os.stat(self.root / guest.lstrip("/"))

    def xattr_errno(self, guest):
        host = self.root / guest.lstrip("/")
        for attr in ("user.WINEREPARSE", "user.DOSATTRIB"):
            try:
                os.getxattr(host, attr)
            except OSError as e:
                return e.errno
        return 0

    def test_answers_are_the_guest_view(self):
        lines, counts = self.run_walk("2000", self.names + self.extra)
        self.assertEqual(counts["misses"], 0)
        got = {tuple(l.split()[:2]): l for l in lines}
        for name in self.names:
            st = self.guest_stat(PROTON + "/" + name)
            self.assertEqual(got[("stat", name)], "stat %s mode %o ino %d size %d" % (name, st.st_mode, st.st_ino, st.st_size))
            no = self.xattr_errno(PROTON + "/" + name)
            self.assertEqual(got[("xattr", name)], "xattr %s %d %d" % (name, no, no))
        sd = self.guest_stat(SD_PROTON + "/sd.dll")
        self.assertEqual(got[("stat", "sd.dll")], "stat sd.dll mode %o ino %d size 4" % (sd.st_mode, sd.st_ino))
        plain = os.stat(self.root / SYSTEM32.lstrip("/") / "plain.txt")
        self.assertEqual(got[("stat", "rel.txt")], "stat rel.txt mode %o ino %d size 5" % (plain.st_mode, plain.st_ino))
        self.assertEqual(got[("stat", "dangling.dll")], "stat dangling.dll err 2")
        self.assertEqual(got[("xattr", "dangling.dll")], "xattr dangling.dll 2 2")
        real = self.guest_stat("/opt/real/dir")
        self.assertEqual(got[("stat", "twohop")], "stat twohop mode %o ino %d size %d" % (real.st_mode, real.st_ino, real.st_size))
        self.assertEqual(got[("stat", "dotted")], "stat dotted mode %o ino %d size %d" % (real.st_mode, real.st_ino, real.st_size))
        lib0 = self.guest_stat(PROTON + "/lib00.dll")
        self.assertEqual(got[("stat", "dotdot.dll")], "stat dotdot.dll mode %o ino %d size 1" % (lib0.st_mode, lib0.st_ino))
        pdir = self.guest_stat(PROTON)
        self.assertEqual(got[("stat", "protondir")], "stat protondir mode %o ino %d size %d" % (pdir.st_mode, pdir.st_ino, pdir.st_size))
        up = self.guest_stat(SYSTEM32)
        self.assertEqual(got[("up", "drivers")], "up drivers mode %o ino %d size %d" % (up.st_mode, up.st_ino, up.st_size))

    def test_same_answers_with_and_without_the_cache(self):
        cached, _ = self.run_walk("2000", self.names + self.extra)
        uncached, counts = self.run_walk("0", self.names + self.extra)
        self.assertEqual(counts["misses"], 0)
        self.assertEqual(cached, uncached)

    def test_a_followed_link_does_not_walk_every_component_of_its_target(self):
        # stat + 2 getxattr of one absolute link: each resolves the link name (one lstat with the parent cached),
        # reads it, and must then reach the target through the verified-parent cache too: a few calls, not one
        # newfstatat per component of a 12-component target (B9: ~3 full walks per system32 entry before).
        _, counts = self.run_walk("2000", self.names)
        self.assertEqual(counts["misses"], 0)
        self.assertEqual(counts["links"], LINKS)
        per_link = counts["link_calls"] / LINKS
        print("kernel calls per symlinked entry: %.1f" % per_link)
        self.assertLessEqual(per_link, 16, "kernel calls per symlinked entry: %.1f" % per_link)


if __name__ == "__main__":
    unittest.main()
