"""Golden test (ledger row 13, 4dc73eff + bridge 684f731): the report and Send logs name the real MaliBridge version.

The MaliBridge version the app shows (build.gradle's maliBridgeVersion, which also decides when the bridge
libraries are rebuilt) is `git describe` of external/MaliBridge. Without --tags it ignores lightweight tags, so
the build of a release commit named itself with a bare hash or the previous release's name; that reached the
player-facing report twice (v0.5.3 -> v0.5.4) and was caught by hand. This takes the describe arguments out of
build.gradle and runs them in a repository made here: a lightweight release tag on HEAD must name the release,
and a tag that is not a release (no v<digit>) must not. Nothing is read from the host's repositories or config.
"""
import os
import re
import subprocess
import tempfile
import unittest
from pathlib import Path

GRADLE = Path(__file__).resolve().parents[2] / 'app/build.gradle'


def describe_args():
    line = next((l for l in GRADLE.read_text().splitlines() if l.startswith('def maliBridgeVersion')), None)
    assert line, 'build.gradle has no maliBridgeVersion'
    args = re.search(r"gitValue\(\[(.*)\]\)\s*\?:", line).group(1)
    words = [w.strip().strip("'") for w in args.split(',')]
    assert words[:3] == ['-C', 'external/MaliBridge', 'describe'], words
    return words[3:]


def git(repo, *args):
    env = dict(os.environ, GIT_CONFIG_GLOBAL='/dev/null', GIT_CONFIG_SYSTEM='/dev/null',
               GIT_AUTHOR_NAME='t', GIT_AUTHOR_EMAIL='t@t', GIT_COMMITTER_NAME='t', GIT_COMMITTER_EMAIL='t@t')
    out = subprocess.run(['git', '-C', str(repo), *args], env=env, capture_output=True, text=True, check=True)
    return out.stdout.strip()


class MaliBridgeVersionDescribeTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.repo = Path(self.tmp.name) / 'MaliBridge'
        git(self.tmp.name, 'init', '-q', str(self.repo))
        git(self.repo, 'commit', '-q', '--allow-empty', '-m', 'one')

    def tearDown(self):
        self.tmp.cleanup()

    def test_a_lightweight_release_tag_names_the_build(self):
        git(self.repo, 'tag', 'v9.9.9')
        self.assertEqual(git(self.repo, 'describe', *describe_args()), 'v9.9.9')

    def test_a_commit_after_the_tag_is_named_from_the_tag_and_other_tags_are_skipped(self):
        git(self.repo, 'tag', 'v9.9.9')
        git(self.repo, 'commit', '-q', '--allow-empty', '-m', 'two')
        git(self.repo, 'tag', 'nightly')
        name = git(self.repo, 'describe', *describe_args())
        self.assertRegex(name, r'^v9\.9\.9-1-g[0-9a-f]+$')


if __name__ == '__main__':
    unittest.main()
