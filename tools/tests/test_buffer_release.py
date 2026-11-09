"""Exercise the compositor's real wl_buffer release paths with a client that checks them the way
gamescope's nested Wayland backend does ("Compositor released us but we were not acquired")."""
import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest

REPO = Path(__file__).resolve().parents[2]
SOURCE = Path(os.environ.get('BUFFER_RELEASE_SOURCE',
                             REPO / 'app/src/main/cpp/waylandcomp/src/compositor.c'))


def function(source, head):
    start = source.index(head)
    return source[start:source.index('\n}\n', start) + 3]


class BufferReleaseTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        compiler = shutil.which(os.environ.get('CC', 'cc'))
        if compiler is None:
            raise unittest.SkipTest('A host C compiler is required')
        source = SOURCE.read_text()
        start = source.index('static void perf_note_release(int64_t since_ns) {')
        end = source.index('\n}\n', source.index('static void release_buffer(struct surface *s,')) + 3
        parts = [source[start:end],
                 function(source, 'static void drop_dmabuf(struct surface *s, int paced) {'),
                 function(source, 'void droiddeck_release_buffer(struct surface *s,')]
        cls.temp = tempfile.TemporaryDirectory(prefix='droiddeck-buffer-release-')
        cls.addClassCleanup(cls.temp.cleanup)
        folder = Path(cls.temp.name)
        (folder / 'release_paths.inc').write_text('\n'.join(parts))
        flags = ['-DHAVE_BUFFER_COMMITTED'] if 'static void buffer_committed(' in parts[0] else []
        cls.binary = folder / 'buffer-release-test'
        subprocess.run([compiler, '-std=gnu11', '-Wall', '-Wno-unused-function', *flags, '-I', str(folder),
                        str(REPO / 'tools/tests/buffer_release_harness.c'), '-o', str(cls.binary)],
                       check=True, capture_output=True, text=True)

    def run_case(self, case):
        result = subprocess.run([str(self.binary), case], capture_output=True, text=True)
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
        return result

    def test_plane_move_keeps_buffer_until_last_surface_lets_go(self):
        self.run_case('plane_move')

    def test_move_back_before_paced_release_is_not_released_on_screen(self):
        self.run_case('move_back')

    def test_same_surface_shows_buffer_again_before_its_release(self):
        self.run_case('reshown')

    def test_destroyed_surface_does_not_release_a_buffer_another_shows(self):
        self.run_case('destroy_shared')

    def test_one_release_when_two_surfaces_drop_the_same_buffer(self):
        self.run_case('double_queue')

    def test_layer_release_waits_for_a_buffer_shown_again(self):
        self.run_case('layer')

    def test_second_release_without_a_commit_is_blocked(self):
        self.run_case('double_guard')

    def check_one_surface(self, case):
        golden = (REPO / f'tools/tests/buffer_release_{case}.txt').read_text()
        self.assertEqual(self.run_case(case).stdout, golden)

    def test_one_surface_paced_release_times_unchanged(self):
        self.check_one_surface('one_surface_paced')

    def test_one_surface_refresh_paced_release_times_unchanged(self):
        self.check_one_surface('one_surface_refresh')

    def test_one_surface_unpaced_release_times_unchanged(self):
        self.check_one_surface('one_surface_unpaced')


if __name__ == '__main__':
    unittest.main()
