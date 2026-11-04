"""Real HTTP regression for existing-UI repair versus new-release verification.

Run on Linux (the deployment target) with Python's standard library and Bash.
No service configuration, database or business files are touched.
"""
import functools
import http.server
import os
from pathlib import Path
import subprocess
import tempfile
import threading
import unittest

ROOT = Path(__file__).resolve().parents[1]
BASH = os.environ.get('AUDIT_TEST_BASH', 'bash')


class HttpResourcesTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(prefix='audit-http-')
        self.release = Path(self.temp.name)
        self.dist = self.release / 'frontend/dist'
        (self.dist / 'assets').mkdir(parents=True)
        (self.dist / 'index.html').write_bytes(b'<script src="/assets/app.js"></script>')
        (self.dist / 'assets/app.js').write_bytes(b'window.app=true;')
        self.denied = set()
        self.override = {}
        self.auth_status = 401
        self.seen = []
        fixture = self

        class Handler(http.server.SimpleHTTPRequestHandler):
            def log_message(self, *_):
                pass

            def do_GET(self):
                fixture.seen.append(self.path)
                if self.path.startswith('/api/v1/'):
                    self.send_response(fixture.auth_status)
                    self.end_headers()
                elif self.path in fixture.denied:
                    self.send_response(403)
                    self.end_headers()
                elif self.path in fixture.override:
                    self.send_response(200)
                    self.end_headers()
                    self.wfile.write(fixture.override[self.path])
                else:
                    super().do_GET()

        self.server = http.server.ThreadingHTTPServer(
            ('127.0.0.1', 0), functools.partial(Handler, directory=str(self.dist)))
        self.thread = threading.Thread(target=self.server.serve_forever, daemon=True)
        self.thread.start()

    def tearDown(self):
        self.server.shutdown()
        self.server.server_close()
        self.thread.join()
        self.temp.cleanup()

    def run_check(self, mode='verify'):
        env = dict(os.environ, AUDIT_TEST_RELEASE=str(self.release),
                   AUDIT_TEST_HTTP=f'http://127.0.0.1:{self.server.server_port}',
                   AUDIT_TEST_MODE=mode)
        return subprocess.run([BASH, '-c',
            'set -Eeuo pipefail; source scripts/offline/customer-v10/common.sh; '
            'http_base="$AUDIT_TEST_HTTP"; host_header=(); '
            'check_http "$AUDIT_TEST_RELEASE" "$AUDIT_TEST_MODE"'],
            cwd=ROOT, env=env, capture_output=True, text=True)

    def test_existing_stale_forbidden_resource_does_not_block_or_get_fetched(self):
        (self.dist / 'assets/old.js').write_bytes(b'stale')
        self.denied.add('/assets/old.js')
        result = self.run_check('existing')
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
        self.assertNotIn('/assets/old.js', self.seen)
        self.assertIn('HTTP_AUTH=PASS', result.stdout)

    def test_existing_broken_entry_can_be_repaired(self):
        for denied in (False, True):
            with self.subTest(denied=denied):
                self.override['/'] = b'old modified entry'
                if denied:
                    self.denied.add('/')
                result = self.run_check('existing')
                self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
                self.assertIn('EXISTING_HTTP_ENTRY=WARN', result.stdout)

    def test_new_release_validates_actual_http_bytes(self):
        result = self.run_check()
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
        self.assertIn('/assets/app.js', self.seen)
        self.assertIn('HTTP_INDEX_ASSETS_AUTH=PASS', result.stdout)

    def test_new_forbidden_asset_stops_and_identifies_resource(self):
        self.denied.add('/assets/app.js')
        result = self.run_check()
        self.assertNotEqual(result.returncode, 0)
        self.assertIn('HTTP_RESOURCE_FAILED asset=assets/app.js', result.stdout)
        self.assertIn('HTTP_ASSET_MISMATCH', result.stdout)

    def test_new_mismatched_asset_stops(self):
        self.override['/assets/app.js'] = b'wrong version'
        result = self.run_check()
        self.assertNotEqual(result.returncode, 0)
        self.assertIn('HTTP_ASSET_MISMATCH', result.stdout)

    def test_new_mismatched_index_stops(self):
        self.override['/'] = b'wrong entry'
        result = self.run_check()
        self.assertNotEqual(result.returncode, 0)
        self.assertIn('HTTP_INDEX_ROOT_MISMATCH', result.stdout)

    def test_auth_boundary_remains_required_in_both_modes(self):
        self.auth_status = 200
        for mode in ('existing', 'verify'):
            with self.subTest(mode=mode):
                result = self.run_check(mode)
                self.assertNotEqual(result.returncode, 0)
                self.assertIn('AUTH_BOUNDARY', result.stdout)

    def test_unknown_mode_never_silently_skips_validation(self):
        result = self.run_check('typo')
        self.assertNotEqual(result.returncode, 0)
        self.assertIn('HTTP_CHECK_MODE_INVALID', result.stdout)


if __name__ == '__main__':
    unittest.main(verbosity=2)
