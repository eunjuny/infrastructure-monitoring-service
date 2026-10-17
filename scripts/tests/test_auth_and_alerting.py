"""Offline checks for CI secret separation, sanitized reports and bounded inbox."""
import importlib.util
import os
from pathlib import Path
import subprocess
from tempfile import TemporaryDirectory
import unittest

ROOT = Path(__file__).resolve().parents[2]


def load(name, path):
    spec = importlib.util.spec_from_file_location(name, ROOT / path)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


AUTH = load('auth_local', 'scripts/auth-local.py')
RUNNER = load('test_auth_runner', 'scripts/test-auth.py')
RECEIVER = load('alert_receiver', 'infra/alertmanager/receiver.py')


class AuthenticationAndAlertsTest(unittest.TestCase):
    def test_smtp_file_revision_recreates_readers_without_exposing_secret_values(self):
        with TemporaryDirectory(prefix='monitoring-mail-revision-') as directory:
            local = Path(directory)
            smtp = local / 'smtp.json'
            smtp.write_text('fixture-private-smtp-content')
            credentials = {'bootstrap': 'fixture', 'appDatabase': 'fixture', 'identityDatabase': 'fixture'}
            AUTH.write_environment(local, credentials)
            before = (local / 'compose.env').read_text()
            os.utime(smtp, ns=(smtp.stat().st_atime_ns, smtp.stat().st_mtime_ns + 1000000))
            AUTH.write_environment(local, credentials)
            after = (local / 'compose.env').read_text()
            self.assertNotEqual(before, after)
            self.assertNotIn('fixture-private-smtp-content', after)
            self.assertEqual(2, (ROOT / 'compose.auth.yaml').read_text().count('io.infrastructure.smtp-revision:'))

    def cleanup_after_failure(self, stage):
        with TemporaryDirectory(prefix='monitoring-auth-cleanup-') as directory:
            temporary = Path(directory)
            shim = temporary / 'python3'
            shim.write_text('#!/bin/sh\nprintf "%s\\n" "$*" >> "$CI_FIXTURE_LOG"\n'
                            'if [ "$*" = "$CI_FIXTURE_FAIL" ]; then exit 7; fi\n')
            shim.chmod(0o700)
            log = temporary / 'calls.log'
            result = subprocess.run(['bash', 'scripts/ci-auth.sh'], cwd=ROOT, capture_output=True,
                                    env={**os.environ, 'PATH': directory + os.pathsep + os.environ['PATH'],
                                         'CI_FIXTURE_LOG': str(log), 'CI_FIXTURE_FAIL': stage})
            self.assertEqual(7, result.returncode)
            self.assertEqual('scripts/auth-local.py --ci --stop', log.read_text().splitlines()[-1])

    def test_cleanup_runs_when_authentication_tests_fail(self):
        self.cleanup_after_failure('scripts/test-auth.py --ci')

    def test_cleanup_runs_when_environment_setup_fails(self):
        self.cleanup_after_failure('scripts/auth-local.py --ci')

    def test_ci_redirects_do_not_reuse_local_browser_origin(self):
        credentials = {'users': {key: {'password': 'fixture-only'} for key in ['admin', 'a', 'b']}}
        realm = AUTH.realm(credentials, 'http://127.0.0.1:5186')
        client = realm['clients'][1]
        self.assertEqual(['http://127.0.0.1:5186/*'], client['redirectUris'])
        self.assertEqual('S256', client['attributes']['pkce.code.challenge.method'])
        self.assertFalse(client['directAccessGrantsEnabled'])
        self.assertEqual(10, len(realm['users'][1]['groups']))
        self.assertEqual('/cluster-11', realm['users'][2]['groups'][0])
        self.assertEqual(['admin', 'user1', 'user2'], [user['username'] for user in realm['users']])
        self.assertEqual('infra-monitor', realm['loginTheme'])

    def test_login_theme_inherits_security_templates_and_is_read_only_mounted(self):
        theme = ROOT / 'infra/keycloak/themes/infra-monitor/login'
        self.assertIn('parent=keycloak', (theme / 'theme.properties').read_text())
        self.assertFalse(list(theme.glob('*.ftl')))
        self.assertIn('css/infra-monitor.css', (theme / 'theme.properties').read_text())
        self.assertIn('/opt/keycloak/themes/infra-monitor:ro', (ROOT / 'compose.auth.yaml').read_text())

    def test_existing_legacy_names_fail_before_reprovisioning_without_exposing_credentials(self):
        credentials = {'users': {key: {'username': username, 'password': 'fixture-private'}
                                for key, username in [('admin', 'admin'), ('a', 'user1'), ('b', 'user2')]}}
        AUTH.validate_account_names(credentials)
        credentials['users']['a']['username'] = 'user-a'
        with self.assertRaises(ValueError) as captured:
            AUTH.validate_account_names(credentials)
        self.assertIn('Legacy local account names', str(captured.exception))
        self.assertNotIn('fixture-private', str(captured.exception))

    def test_junit_drops_sensitive_failure_diagnostics(self):
        report = {'suites': [{'specs': [{'title': 'role isolation', 'tests': [{'results': [{'status': 'failed',
                  'duration': 100, 'error': {'message': 'secret-example-password'}}]}]}]}]}
        xml = RUNNER.junit(report)
        self.assertIn('failures="1"', xml)
        self.assertNotIn('secret-example-password', xml)
        self.assertIn('role isolation', xml)

    def test_runner_masks_passwords_and_callback_codes(self):
        credentials = {'users': {'admin': {'password': 'private-example'}}}
        output = RUNNER.redact('private-example http://localhost/?code=private-code&state=private-state', credentials)
        for value in ['private-example', 'private-code', 'private-state']:
            self.assertNotIn(value, output)

    def test_inbox_only_keeps_allowlisted_fields(self):
        body = {'alerts': [{'labels': {'job': 'infrastructure-api', 'alertname': 'MockBatchFailures', 'private': 'do-not-save'},
                            'status': 'resolved', 'annotations': {'private': 'do-not-save'}}]}
        rows = RECEIVER.sanitize(body)
        self.assertEqual('resolved', rows[0]['status'])
        self.assertNotIn('do-not-save', str(rows))
        self.assertEqual({'name', 'status', 'receivedAt'}, rows[0].keys())

    def test_inbox_rejects_oversized_unknown_or_invalid_alerts(self):
        for body in [{'alerts': [{}] * 21}, {'alerts': [{}]}, {'alerts': 'invalid'}, []]:
            with self.subTest(body=body):
                with self.assertRaises(ValueError):
                    RECEIVER.sanitize(body)
        self.assertEqual(200, RECEIVER.EVENTS.maxlen)


if __name__ == '__main__':
    unittest.main()
