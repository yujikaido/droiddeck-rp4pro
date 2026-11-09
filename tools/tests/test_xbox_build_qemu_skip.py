"""Golden test (ledger row 101, 8cbb2fc0d): the Xbox backend build under qemu-user skips the one test that
starts its own aarch64 executable.

tools/xbox/build.sh builds the aarch64 backend on an x86_64 host in a container with qemu-user and no
binfmt_misc, where a test that spawns its own aarch64 child cannot run, so the whole build failed. Under a
qemu runner that test is skipped; a native arm64 build and any other runner run everything.
This runs the real test-selection block of build.sh with stub `cargo` and `uname` programs (nothing is
built, nothing is read from the host).
"""
import os
import re
import stat
import subprocess
import tempfile
import unittest
from pathlib import Path

BUILD = Path(__file__).resolve().parents[2] / 'tools/xbox/build.sh'
SKIP = 'chat_session_policy_lookup_keeps_stdout_machine_readable'


def selection_block():
    text = BUILD.read_text()
    start = text.index('if [[ $(uname -m) = aarch64 || -n ${CARGO_TARGET_AARCH64_UNKNOWN_LINUX_GNU_RUNNER:-} ]]; then')
    end = text.index('\nfi\n', start) + 4
    return text[start:end]


def run_block(machine, runner):
    with tempfile.TemporaryDirectory() as tmp:
        tmp = Path(tmp)
        bin_dir = tmp / 'bin'
        bin_dir.mkdir()
        calls = tmp / 'cargo-calls'
        (bin_dir / 'cargo').write_text(f'#!/bin/bash\necho "$*" >> "{calls}"\n')
        (bin_dir / 'uname').write_text(f'#!/bin/bash\necho {machine}\n')
        for tool in bin_dir.iterdir():
            tool.chmod(tool.stat().st_mode | stat.S_IXUSR)
        script = tmp / 'block.sh'
        script.write_text('#!/usr/bin/env bash\nset -euo pipefail\n' + selection_block())
        env = {'PATH': f'{bin_dir}:/usr/bin:/bin'}
        if runner is not None:
            env['CARGO_TARGET_AARCH64_UNKNOWN_LINUX_GNU_RUNNER'] = runner
        done = subprocess.run(['bash', str(script)], env=env, capture_output=True, text=True, timeout=30)
        assert done.returncode == 0, done.stderr
        return calls.read_text().splitlines() if calls.exists() else []


class XboxBuildQemuSkipTest(unittest.TestCase):
    def test_under_qemu_the_spawning_test_is_skipped(self):
        calls = run_block('x86_64', 'qemu-aarch64')
        self.assertEqual(len(calls), 3)
        cli = calls[0]
        self.assertIn('-p xodus-cli', cli)
        self.assertTrue(cli.endswith(f'-- --skip {SKIP}'), cli)
        # the other two test runs are untouched
        self.assertNotIn('--skip', calls[1])
        self.assertNotIn('--skip', calls[2])

    def test_a_native_arm64_build_runs_every_test(self):
        calls = run_block('aarch64', None)
        self.assertEqual(len(calls), 3)
        self.assertTrue(all('--skip' not in c for c in calls), calls)

    def test_another_runner_runs_every_test(self):
        calls = run_block('x86_64', 'some-other-runner')
        self.assertEqual(len(calls), 3)
        self.assertTrue(all('--skip' not in c for c in calls), calls)

    def test_an_x86_host_without_a_runner_builds_without_testing(self):
        self.assertEqual(run_block('x86_64', None), [])


if __name__ == '__main__':
    unittest.main()
