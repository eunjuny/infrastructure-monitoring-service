"""Private, bounded recipient configuration for native Alertmanager email delivery."""
import json
import os
from pathlib import Path
import re
import threading
import urllib.request

ADDRESS = re.compile(r"[A-Za-z0-9.!#$%&'*+/=?^_`{|}~-]+@[A-Za-z0-9](?:[A-Za-z0-9.-]*[A-Za-z0-9])?\.[A-Za-z]{2,63}")


def valid_address(value):
    return (isinstance(value, str) and len(value) <= 254 and ADDRESS.fullmatch(value) is not None
            and len(value.split('@')[0]) <= 64 and '..' not in value
            and all(not part.startswith('-') and not part.endswith('-') for part in value.split('@')[1].split('.')))


class EmailSettings:
    def __init__(self, directory, smtp_file, reload_configuration=None, allow_test_plaintext=False):
        self.directory = Path(directory)
        self.directory.mkdir(mode=0o700, parents=True, exist_ok=True)
        self.directory.chmod(0o700)
        self.lock = threading.Lock()
        self.reload = reload_configuration or self.reload_alertmanager
        self.allow_test_plaintext = allow_test_plaintext
        self.smtp = json.loads(Path(smtp_file).read_text())
        if self.smtp:
            if (set(self.smtp) != {'smarthost', 'from', 'username', 'password'}
                    or not valid_address(self.smtp['from'])
                    or not re.fullmatch(r'[A-Za-z0-9.-]+:[0-9]{1,5}', self.smtp['smarthost'])
                    or not 1 <= int(self.smtp['smarthost'].rsplit(':', 1)[1]) <= 65535
                    or any(not isinstance(self.smtp[key], str) or not 1 <= len(self.smtp[key]) <= 1024
                           or any(char in self.smtp[key] for char in '\r\n\x00') for key in ['username', 'password'])):
                raise ValueError('Invalid private SMTP configuration')
        settings_file = self.directory / 'settings.json'
        self.settings = json.loads(settings_file.read_text()) if settings_file.exists() else {'recipient': '', 'enabled': False}
        self.validate(self.settings)
        if not self.smtp:
            self.settings['enabled'] = False
        self.write_configuration(self.settings)

    @staticmethod
    def reload_alertmanager():
        # Fixed Compose destination only; never accept a user-controlled URL or redirects.
        class NoRedirect(urllib.request.HTTPRedirectHandler):
            def redirect_request(self, *_):
                return None
        request = urllib.request.Request('http://alertmanager:9093/-/reload', data=b'', method='POST')
        with urllib.request.build_opener(NoRedirect).open(request, timeout=2) as response:
            if response.status != 200:
                raise OSError('Configuration reload rejected')

    @staticmethod
    def validate(settings):
        if (not isinstance(settings, dict) or set(settings) != {'recipient', 'enabled'}
                or type(settings['enabled']) is not bool
                or (settings['recipient'] != '' and not valid_address(settings['recipient']))
                or (settings['enabled'] and not settings['recipient'])):
            raise ValueError('Use one plain email address and a boolean enabled flag')

    def atomic(self, name, value):
        target = self.directory / name
        temporary = target.with_suffix('.tmp')
        descriptor = os.open(temporary, os.O_WRONLY | os.O_CREAT | os.O_TRUNC, 0o600)
        with os.fdopen(descriptor, 'w') as output:
            output.write(value)
        temporary.chmod(0o600)
        os.replace(temporary, target)

    def configuration(self, settings):
        receiver = {'name': 'local-inbox', 'webhook_configs': [{
            'url': 'http://alert-receiver:8090/webhook', 'send_resolved': True, 'max_alerts': 20,
            'http_config': {'follow_redirects': False}}]}
        if settings['enabled'] and self.smtp:
            receiver['email_configs'] = [{
                'to': settings['recipient'], 'from': self.smtp['from'], 'smarthost': self.smtp['smarthost'],
                'auth_username': self.smtp['username'], 'auth_password_file': '/var/lib/alert-email/smtp-password',
                'require_tls': not self.allow_test_plaintext, 'send_resolved': True}]
        return {'global': {'resolve_timeout': '2m'},
                'route': {'receiver': 'local-inbox', 'group_by': ['job', 'alertname'],
                          'group_wait': '5s', 'group_interval': '15s', 'repeat_interval': '4h'},
                'receivers': [receiver], 'inhibit_rules': [{
                    'source_matchers': ['alertname="BackendScrapeUnavailable"'],
                    'target_matchers': ['alertname=~"ResourceApiErrorBurst|MockBatchFailures"'], 'equal': ['job']}]}

    def write_configuration(self, settings):
        if self.smtp:
            self.atomic('smtp-password', self.smtp['password'])
        # JSON is a YAML subset accepted by Alertmanager; no new YAML dependency.
        self.atomic('alertmanager.yml', json.dumps(self.configuration(settings)) + '\n')
        self.atomic('settings.json', json.dumps(settings) + '\n')

    def snapshot(self):
        with self.lock:
            return {**self.settings, 'smtpConfigured': bool(self.smtp), 'available': True}

    def update(self, settings):
        self.validate(settings)
        if settings['enabled'] and not self.smtp:
            raise ValueError('Configure SMTP before enabling email delivery')
        with self.lock:
            previous = self.settings
            try:
                self.write_configuration(settings)
                self.reload()
            except Exception:
                self.write_configuration(previous)
                try:
                    self.reload()
                except Exception:
                    pass  # Report failure; never claim the requested configuration was applied.
                raise OSError('Alertmanager configuration was not confirmed') from None
            self.settings = dict(settings)
        return self.snapshot()
