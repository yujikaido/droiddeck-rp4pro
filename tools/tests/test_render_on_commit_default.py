"""Golden test (ledger row 98, baa4ae3f0): the compositor draws on the screen tick unless renderOnCommit is on.

Balatro lost 9 fps on the Galaxy Tab S11 Ultra (96 instead of 105) when every game frame was drawn the moment
it arrived. The Kotlin default is covered by RenderOnCommitDefaultTest; this covers the native side: until the
app hands the pref over the flag is off (no initializer), and the app hands the pref over at compositor start.
"""
import re
import unittest
from pathlib import Path

REPO = Path(__file__).resolve().parents[2]
SRC = REPO / 'app/src/main'


class RenderOnCommitDefaultNativeTest(unittest.TestCase):
    def test_the_native_flag_starts_off(self):
        c = (SRC / 'cpp/waylandcomp/src/compositor.c').read_text()
        decl = re.search(r'^volatile int g_render_on_commit\b([^;]*);', c, re.MULTILINE)
        self.assertIsNotNone(decl, 'g_render_on_commit declaration')
        self.assertEqual(decl.group(1).strip(), '', 'no initializer: the flag is 0 until the app sets it')

    def test_the_app_hands_the_pref_to_the_compositor(self):
        host = (SRC / 'java/com/droiddeck/launcher/wayland/CompositorHost.kt').read_text()
        self.assertIn('WaylandCompositor.nativeSetRenderOnCommit(SessionPrefs.renderOnCommit(context))', host)
        jni = (SRC / 'cpp/waylandcomp/src/waylandcomp_jni.c').read_text()
        self.assertIn('g_render_on_commit = on ? 1 : 0;', jni)

    def test_the_pref_default_in_source_is_off(self):
        kt = (SRC / 'java/com/droiddeck/launcher/session/SessionPrefs.kt').read_text()
        self.assertIn('getBoolean("renderOnCommit", false)', kt)


if __name__ == '__main__':
    unittest.main()
