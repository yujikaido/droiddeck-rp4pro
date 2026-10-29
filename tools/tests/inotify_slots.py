"""Whether this host can hand the test one more inotify instance.

fs.inotify.max_user_instances (128 by default) counts every process of the same user. A desktop
with stacked sessions (tracker-miner, gvfs, gnome-session) can use them all up, and then a test
that needs a watch fails for reasons that have nothing to do with the code under test.
"""
import ctypes
import errno
import os

IN_CLOEXEC = 0o2000000


def _probe():
    try:
        libc = ctypes.CDLL(None, use_errno=True)
        init = libc.inotify_init1
    except (OSError, AttributeError):
        return None
    fd = init(IN_CLOEXEC)
    if fd >= 0:
        os.close(fd)
        return None
    code = ctypes.get_errno()
    if code not in (errno.EMFILE, errno.ENFILE):
        return None
    try:
        limit = open("/proc/sys/fs/inotify/max_user_instances").read().strip()
    except OSError:
        limit = "?"
    return ("this host has no free inotify instance (%s; fs.inotify.max_user_instances=%s is used up by "
            "uid %d's other processes). Close stale desktop sessions, or run the test as root in Docker"
            % (errno.errorcode.get(code, code), limit, os.getuid()))


NO_SLOT = _probe()
