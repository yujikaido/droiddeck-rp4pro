"""Golden test (ledger rows 44 and 47, 5a195dd21): events.jsonl says why a session ended.

Some phones (vivo) keep the app's logcat out of app.log, so the guest.exited event's reason is the only record
of why a session ended. SessionStopSourceTest covers the source travelling in the stop intent; this covers the
service side: the reason reaches the event, every path that ends a session names itself, the session menu's
Quit animation logs as Stop. Only sources are read.
"""
import re
import unittest
from pathlib import Path

REPO = Path(__file__).resolve().parents[2]
SERVICE = REPO / 'app/src/main/java/com/droiddeck/launcher/session/SessionService.kt'


class StopReasonEventsTest(unittest.TestCase):
    def setUp(self):
        self.src = SERVICE.read_text()

    def test_the_reason_is_written_into_the_exit_event(self):
        self.assertIn('private fun stopSession(status: Int, reason: String)', self.src)
        self.assertIn('SessionEvents.record("guest.exited", mapOf("status" to status, "reason" to reason))', self.src)

    def test_every_stop_names_its_reason(self):
        # A session start ends through stopIfCurrent(gen, status, reason) (row 184: a start that is no longer the
        # current one must not stop the newer session); it calls stopSession with the same reason.
        calls = re.findall(r'\bstopSession\(([^()]*(?:\([^()]*\)[^()]*)*)\)', self.src)
        calls = [c for c in calls if not c.startswith('status: Int')]
        starts = re.findall(r'\bstopIfCurrent\(([^()]*(?:\([^()]*\)[^()]*)*)\)', self.src)
        starts = [c for c in starts if not c.startswith('gen: Int')]
        self.assertIn('if (gen == sessionGen) stopSession(status, reason)', self.src)
        self.assertGreaterEqual(len(calls) + len(starts), 12)
        for call in calls:
            self.assertIn(',', call, f'stopSession({call}) names no reason')
        for call in starts:
            self.assertEqual(call.count(','), 2, f'stopIfCurrent({call}) names no reason')
            self.assertRegex(call.split(',', 2)[2].strip(), r'^"', f'stopIfCurrent({call}) names no reason')

    def test_the_notification_stop_is_named_when_no_source_is_sent(self):
        self.assertIn('intent.getStringExtra(EXTRA_STOP_SOURCE) ?: STOP_NOTIFICATION', self.src)
        self.assertIn('const val STOP_NOTIFICATION = "the notification\'s Stop"', self.src)

    def test_the_session_menu_stop_has_one_name_with_or_without_the_quit_animation(self):
        self.assertIn('const val STOP_FROM_MENU = "Stop in the session menu"', self.src)
        users = list((REPO / 'app/src/main/java').rglob('*.kt'))
        used = sum(p.read_text().count('SessionService.STOP_FROM_MENU') for p in users)
        self.assertGreaterEqual(used, 2, 'Stop and Quit in the session menu both log as the menu Stop')


if __name__ == '__main__':
    unittest.main()
