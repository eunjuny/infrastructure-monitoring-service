"""No external SMTP: recipient validation, TLS defaults and reload rollback."""
import importlib.util
import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import Mock

ROOT = Path(__file__).resolve().parents[2]
SPEC = importlib.util.spec_from_file_location('email_settings', ROOT / 'infra/alertmanager/email_settings.py')
EMAIL = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(EMAIL)


class AlertEmailTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name)
        self.smtp = self.root / 'smtp.json'
        self.smtp.write_text('{}')
        self.reload = Mock()

    def service(self, configured=False):
        if configured:
            self.smtp.write_text(json.dumps({'smarthost': 'smtp.example.invalid:587',
                'from': 'sender@example.invalid', 'username': 'fixture', 'password': 'fixture-only-not-a-secret'}))
        return EMAIL.EmailSettings(self.root / 'state', self.smtp, self.reload)

    def test_address_can_be_saved_without_smtp_but_cannot_be_enabled(self):
        service = self.service()
        result = service.update({'recipient': 'admin@example.invalid', 'enabled': False})
        self.assertFalse(result['smtpConfigured'])
        self.assertFalse(result['enabled'])
        with self.assertRaises(ValueError):
            service.update({'recipient': 'admin@example.invalid', 'enabled': True})
        self.assertEqual('admin@example.invalid', self.service().snapshot()['recipient'])

    def test_native_email_keeps_webhook_tls_and_password_file(self):
        service = self.service(True)
        result = service.update({'recipient': 'admin@example.invalid', 'enabled': True})
        config = json.loads((self.root / 'state/alertmanager.yml').read_text())
        receiver = config['receivers'][0]
        self.assertTrue(result['enabled'])
        self.assertEqual(1, len(receiver['webhook_configs']))
        self.assertTrue(receiver['email_configs'][0]['require_tls'])
        self.assertTrue(receiver['email_configs'][0]['send_resolved'])
        self.assertNotIn('auth_password', receiver['email_configs'][0])
        self.assertNotIn('fixture-only-not-a-secret', json.dumps(config))
        self.reload.assert_called_once()

    def test_disabling_removes_email_only_and_clear_recipient_is_supported(self):
        service = self.service(True)
        service.update({'recipient': 'admin@example.invalid', 'enabled': True})
        service.update({'recipient': '', 'enabled': False})
        config = json.loads((self.root / 'state/alertmanager.yml').read_text())
        self.assertNotIn('email_configs', config['receivers'][0])
        self.assertIn('webhook_configs', config['receivers'][0])

    def test_reload_failure_restores_previous_saved_configuration(self):
        service = self.service(True)
        before = (self.root / 'state/alertmanager.yml').read_text()
        self.reload.side_effect = OSError('fixture reload unavailable')
        with self.assertRaises(OSError):
            service.update({'recipient': 'admin@example.invalid', 'enabled': True})
        self.assertEqual(before, (self.root / 'state/alertmanager.yml').read_text())
        self.assertFalse(service.snapshot()['enabled'])
        self.assertEqual('', json.loads((self.root / 'state/settings.json').read_text())['recipient'])

    def test_rejects_injection_multiple_addresses_and_non_boolean_enable(self):
        service = self.service(True)
        for recipient in ['A <a@example.invalid>', 'a@example.invalid,b@example.invalid',
                          'a@example.invalid\r\nBcc: x@example.invalid', 'a..b@example.invalid', 'x' * 65 + '@example.invalid', 'not-mail']:
            with self.subTest(recipient=recipient), self.assertRaises(ValueError):
                service.update({'recipient': recipient, 'enabled': False})
        for enabled in ['true', 1, None]:
            with self.assertRaises(ValueError):
                service.update({'recipient': 'admin@example.invalid', 'enabled': enabled})
        self.reload.assert_not_called()

    def test_private_files_and_restart_keep_recipient(self):
        service = self.service(True)
        service.update({'recipient': 'admin@example.invalid', 'enabled': True})
        for path in (self.root / 'state').iterdir():
            self.assertEqual(0o600, path.stat().st_mode & 0o777)
        self.assertTrue(self.service(True).snapshot()['enabled'])


if __name__ == '__main__':
    unittest.main()
