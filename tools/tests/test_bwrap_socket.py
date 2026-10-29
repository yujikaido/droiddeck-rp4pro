"""droiddeck-bwrap must reach its own build's app even when its caller cleared the environment.

flatpak runs the bwrap that makes an app's ld.so cache with an empty environment, so
DROIDDECK_BWRAP_SOCKET is missing there. The .mali build (and the other variants) listen on
"<package>.bwrap", not the upstream "com.droiddeck.launcher.bwrap", so the script reads the name from
usr/local/share/droiddeck/bwrap-socket, which BwrapSpawner.publishSocket writes at every session start
("ldconfig failed, exit status 256" on a Flathub app's first run).
"""
import os
from pathlib import Path
import re
import shutil
import socket
import struct
import subprocess
import tempfile
import threading
import unittest

REPO = Path(__file__).resolve().parents[2]
SCRIPT = REPO / "tools/linuxfs/overlay/usr/local/bin/droiddeck-bwrap"
SPAWNER = REPO / "app/src/main/java/com/droiddeck/launcher/runtime/BwrapSpawner.kt"
SESSION_FILES = REPO / "app/src/main/java/com/droiddeck/launcher/session/SessionFiles.kt"
VARIANTS = REPO / "tools/release/variants.txt"
PACKAGES = [line.split()[1] for line in VARIANTS.read_text().splitlines()
            if line.strip() and not line.startswith("#")]
UPSTREAM = "com.droiddeck.launcher.bwrap"


def socket_file_constant():
    match = re.search(r'const val SOCKET_FILE = "([^"]+)"', SPAWNER.read_text())
    assert match, "BwrapSpawner.SOCKET_FILE is gone; update this test"
    return match[1]


class FakeApp:
    """Listens like BwrapSpawner on an abstract socket and answers one sandbox request with exit 0."""

    def __init__(self, name):
        self.name, self.requests = name, []
        self.sock = socket.socket(socket.AF_UNIX, socket.SOCK_STREAM)
        self.sock.bind("\0" + name)
        self.sock.listen(4)
        self.thread = threading.Thread(target=self.serve, daemon=True)
        self.thread.start()

    def serve(self):
        try:
            client, _ = self.sock.accept()
        except OSError:
            return
        with client:
            head = b""
            while len(head) < 5:
                head += client.recv(5 - len(head))
            size = struct.unpack(">I", head[1:])[0]
            body = b""
            while len(body) < size:
                body += client.recv(size - len(body))
            self.requests.append(body)
            client.sendall(b"X" + struct.pack(">I", 1) + b"0")

    def close(self):
        self.sock.close()


class BwrapSocketTest(unittest.TestCase):
    def setUp(self):
        self.tmp = Path(tempfile.mkdtemp())
        self.addCleanup(shutil.rmtree, self.tmp, True)
        bin_dir = self.tmp / "usr/local/bin"
        bin_dir.mkdir(parents=True)
        self.script = bin_dir / "droiddeck-bwrap"
        shutil.copy(SCRIPT, self.script)
        self.script.chmod(0o755)

    def publish(self, name):
        target = self.tmp / socket_file_constant()
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_text(name + "\n")

    def run_bwrap(self, env):
        # A bind that is not a no-op, so the script must ask the app (like flatpak's ldconfig call).
        argv = ["/usr/bin/env", "-i"] + ["%s=%s" % kv for kv in env.items()] + [
            "python3", str(self.script), "--bind", str(self.tmp / "missing"), "/run/ld-so-cache-dir",
            "ldconfig", "-X"]
        return subprocess.run(argv, capture_output=True, text=True, timeout=20)

    def served(self, name, env):
        app = FakeApp(name)
        try:
            result = self.run_bwrap(env)
            app.thread.join(5)
        finally:
            app.close()
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertEqual(1, len(app.requests), result.stderr)
        self.assertIn(b'"ldconfig"', app.requests[0])

    def test_empty_environment_reaches_every_variant_and_flavour(self):
        for package in PACKAGES:
            for suffix in ("", ".mali"):
                name = package + suffix + ".bwrap"
                with self.subTest(socket=name):
                    self.publish(name)
                    self.served(name, {})

    def test_environment_still_wins_over_the_file(self):
        self.publish("com.droiddeck.launcher.mali.bwrap")
        self.served("com.droiddeck.launcher.pubgtest.bwrap",
                    {"DROIDDECK_BWRAP_SOCKET": "com.droiddeck.launcher.pubgtest.bwrap"})

    def test_no_file_and_no_environment_keeps_the_upstream_name(self):
        self.served(UPSTREAM, {})

    def test_blank_file_keeps_the_upstream_name(self):
        self.publish("  ")
        self.served(UPSTREAM, {})

    def test_wrong_socket_says_which_one_it_tried(self):
        self.publish("com.droiddeck.launcher.nobody.bwrap")
        result = self.run_bwrap({})
        self.assertEqual(1, result.returncode)
        self.assertIn("not answering on com.droiddeck.launcher.nobody.bwrap", result.stderr)

    def test_app_writes_the_file_the_script_reads(self):
        relative = socket_file_constant()
        # The script sits at /usr/local/bin/droiddeck-bwrap in the rootfs and reads ../share/droiddeck/bwrap-socket.
        self.assertEqual("usr/local/share/droiddeck/bwrap-socket", relative)
        self.assertIn('"..", "share", "droiddeck", "bwrap-socket"', SCRIPT.read_text())
        stage = SESSION_FILES.read_text().split("fun stage(", 1)[1].split("\n    fun ", 1)[0]
        self.assertIn("BwrapSpawner.publishSocket(context, root)", stage)


if __name__ == "__main__":
    unittest.main()
