"""Exercise real shell rollback/drop-in behavior inside isolated fixtures only."""
import os
from pathlib import Path
import subprocess
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[1]
BASH = os.environ.get('AUDIT_TEST_BASH', 'bash')


class ManagedDropinsTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory(prefix='dropin-', dir=ROOT / '.runtime')
        self.root = Path(self.tmp.name)
        self.units = self.root / 'units'
        self.record = self.root / 'record'
        self.record.mkdir()
        for svc in ('backend', 'ai'):
            (self.units / f'audit-{svc}.service.d').mkdir(parents=True)
        self.expected = {svc: f'[Service]\nEnvironmentFile=/opt/audit-agent/current/deploy/update-v10/{svc}.env\n'
                         for svc in ('backend', 'ai')}

    def tearDown(self):
        self.tmp.cleanup()

    def path(self, svc):
        return self.units / f'audit-{svc}.service.d/99-audit-v10.conf'

    def write(self, path, content):
        path.write_text(content, encoding='utf-8', newline='\n')

    def run_shell(self, command):
        env = dict(os.environ, AUDIT_TEST_UNIT_ROOT=self.units.as_posix(),
                   AUDIT_TEST_RECORD=self.record.as_posix())
        return subprocess.run([BASH, '-c',
            'set -eu; source scripts/offline/customer-v10/common.sh; '
            'unit_root="$AUDIT_TEST_UNIT_ROOT"; record="$AUDIT_TEST_RECORD"; '
            'base=/opt/audit-agent; ' + command], cwd=ROOT, env=env,
            capture_output=True, text=True)

    def stage(self, previous):
        for svc in ('backend', 'ai'):
            self.write(self.path(svc), self.expected[svc])
            self.write(self.record / f'{svc}.conf', self.expected[svc])
            if previous:
                self.write(self.record / f'previous-{svc}.conf', self.expected[svc])

    def test_first_update_rollback_removes_only_own_dropins(self):
        self.stage(False)
        other = self.path('backend').parent / '50-customer.conf'
        self.write(other, '[Service]\nEnvironment=CUSTOMER_SETTING=preserved\n')
        before = other.read_bytes()
        result = self.run_shell('check_managed_dropins; restore_dropins')
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
        self.assertTrue(all(not self.path(svc).exists() for svc in self.expected))
        self.assertEqual(other.read_bytes(), before)

    def test_repeat_update_rollback_restores_previous_managed_dropins(self):
        self.stage(True)
        result = self.run_shell('check_managed_dropins; restore_dropins; restore_dropins')
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
        for svc in self.expected:
            self.assertEqual(self.path(svc).read_text(), self.expected[svc])

    def test_unknown_second_dropin_never_partially_changes_first(self):
        self.stage(False)
        self.write(self.path('ai'), '[Service]\nEnvironment=UNMANAGED=1\n')
        before = {svc: self.path(svc).read_bytes() for svc in self.expected}
        for command in ('check_managed_dropins', 'restore_dropins'):
            self.assertNotEqual(self.run_shell(command).returncode, 0)
            self.assertEqual({svc: self.path(svc).read_bytes() for svc in self.expected}, before)

    def test_symlink_rejected_including_dangling(self):
        # Use the target platform's real ln -s semantics, including on Git Bash.
        self.stage(False)
        self.path('ai').unlink()
        result = self.run_shell('ln -s missing-target "$unit_root/audit-ai.service.d/99-audit-v10.conf"; '
                                'check_managed_dropins')
        if not self.path('ai').is_symlink():
            self.skipTest('real POSIX symlinks unavailable; execute this case on Linux')
        self.assertNotEqual(result.returncode, 0)
        self.assertNotEqual(self.run_shell('restore_dropins').returncode, 0)
        self.assertTrue(self.path('backend').exists())

    def test_rollback_health_uses_recorded_loopback_and_host(self):
        self.write(self.record / 'url.txt', 'http://127.0.0.1:8080/\n')
        self.write(self.record / 'host.txt', 'audit.example\n')
        result = self.run_shell('load_record_http; '
            '[[ $http_base == http://127.0.0.1:8080 ]]; '
            '[[ ${host_header[0]} == -H && ${host_header[1]} == "Host: audit.example" ]]; '
            'curl_local(){ [[ $1 == "$http_base/api/v1/health" || $1 == http://127.0.0.1:8001/health ]]; }; health')
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
        self.write(self.record / 'url.txt', 'http://outside.example/\n')
        self.assertNotEqual(self.run_shell('load_record_http').returncode, 0)


if __name__ == '__main__':
    unittest.main()
