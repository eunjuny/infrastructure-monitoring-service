#!/usr/bin/env python3
"""Interactive local SMTP provisioning. Never put secrets in arguments or output."""
from getpass import getpass
import importlib.util
import json
from pathlib import Path
from tempfile import TemporaryDirectory

ROOT = Path(__file__).resolve().parents[1]


def main():
    spec = importlib.util.spec_from_file_location('auth_local', ROOT / 'scripts/auth-local.py')
    auth = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(auth)
    spec = importlib.util.spec_from_file_location('email_settings', ROOT / 'infra/alertmanager/email_settings.py')
    email = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(email)
    local = ROOT / '.local/auth'
    if not (local / 'credentials.json').exists():
        raise SystemExit('Run scripts/auth-local.py first; keep all existing account files')
    values = {key: getpass(prompt).strip() for key, prompt in [
        ('smarthost', 'SMTP host:port (STARTTLS, normally 587; hidden): '),
        ('from', 'Sender email (hidden): '), ('username', 'SMTP username (hidden): '),
        ('password', 'SMTP app password (hidden): ')]}
    try:
        with TemporaryDirectory(prefix='monitoring-private-smtp-') as directory:
            source = Path(directory) / 'smtp.json'
            auth.write_private(source, json.dumps(values))
            email.EmailSettings(Path(directory) / 'config', source)
        auth.write_private(local / 'smtp.json', json.dumps(values) + '\n')
    except (ValueError, TypeError, OSError):
        raise SystemExit('Invalid SMTP settings; values suppressed and existing configuration preserved') from None
    print('Private SMTP settings saved (0600). Restart with python3 scripts/auth-local.py, then enable mail in the ADMIN operations screen.')
    print('No email was sent. Never commit or share the private SMTP file.')


if __name__ == '__main__':
    main()
