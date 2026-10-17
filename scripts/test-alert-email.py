#!/usr/bin/env python3
"""Real Alertmanager + disposable SMTP sink; internal Docker network, no external mail."""
import importlib.util
import json
from pathlib import Path
import secrets
import subprocess
from tempfile import TemporaryDirectory
import time

ROOT = Path(__file__).resolve().parents[1]


def run(*args):
    return subprocess.check_output(['docker', *args], cwd=ROOT, stderr=subprocess.DEVNULL, timeout=30).decode().strip()


def main():
    spec = importlib.util.spec_from_file_location('email_settings', ROOT / 'infra/alertmanager/email_settings.py')
    email = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(email)
    suffix = secrets.token_hex(4)
    network, sink, manager = ['monitoring-email-test-' + part + '-' + suffix for part in ['net', 'sink', 'am']]
    with TemporaryDirectory(prefix='monitoring-email-test-') as directory:
        source = Path(directory) / 'smtp.json'
        source.write_text(json.dumps({'smarthost': 'localhost:2525', 'from': 'sender@example.invalid',
                                      'username': 'fixture', 'password': secrets.token_urlsafe(24)}))
        source.chmod(0o600)
        settings = email.EmailSettings(Path(directory) / 'config', source, lambda: None, allow_test_plaintext=True)
        settings.update({'recipient': 'admin@example.invalid', 'enabled': True})
        try:
            run('network', 'create', '--internal', network)
            run('run', '-d', '--name', sink, '--network', network, '--network-alias', 'alert-receiver',
                '-v', str(ROOT / 'scripts/fixtures/smtp_sink.py') + ':/fixture.py:ro',
                'python:3.13-alpine', 'python', '-B', '/fixture.py')
            # Same disposable network namespace makes localhost PLAIN auth test-only, not external SMTP.
            run('run', '-d', '--name', manager, '--network', 'container:' + sink, '--user', '0:0',
                '-v', str(Path(directory) / 'config') + ':/var/lib/alert-email:ro',
                'prom/alertmanager:v0.34.0', '--config.file=/var/lib/alert-email/alertmanager.yml',
                '--storage.path=/tmp/alertmanager', '--cluster.listen-address=')
            def query(code):
                return json.loads(run('exec', sink, 'python', '-c', code))
            def counts():
                return query('import json,urllib.request; print(urllib.request.urlopen("http://127.0.0.1:8090/",timeout=2).read().decode())')
            def wait(check):
                deadline = time.monotonic() + 40
                while time.monotonic() < deadline:
                    try:
                        if check():
                            return
                    except (ValueError, subprocess.SubprocessError):
                        pass
                    time.sleep(1)
                raise TimeoutError('Internal email delivery timed out')
            wait(lambda: query('import json,urllib.request; r=urllib.request.urlopen("http://127.0.0.1:9093/-/ready",timeout=2); print(json.dumps(r.status==200))'))
            def alert(resolved):
                code = ('import json,urllib.request,datetime; now=datetime.datetime.now(datetime.timezone.utc); '
                        'body=[{"labels":{"alertname":"BackendScrapeUnavailable","job":"infrastructure-api"},'
                        '"startsAt":(now-datetime.timedelta(minutes=1)).isoformat(),'
                        '"endsAt":(now+datetime.timedelta(seconds=' + ('-1' if resolved else '120') + ')).isoformat()}]; '
                        'request=urllib.request.Request("http://127.0.0.1:9093/api/v2/alerts",data=json.dumps(body).encode(),'
                        'headers={"Content-Type":"application/json"}); '
                        'print(json.dumps(urllib.request.urlopen(request,timeout=3).status==200))')
                assert query(code)
            alert(False)
            wait(lambda: counts()['firing'] >= 1)
            alert(True)
            wait(lambda: counts()['resolved'] >= 1)
            result = counts()
            assert result['firing'] >= 1 and result['resolved'] >= 1
            print('Native Alertmanager SMTP delivery verified:', json.dumps(result), '(internal fixture only; external mail: 0)')
        finally:
            for container in [manager, sink]:
                subprocess.run(['docker', 'rm', '-f', container], stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
            subprocess.run(['docker', 'network', 'rm', network], stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)


if __name__ == '__main__':
    try:
        main()
    except (OSError, ValueError, AssertionError, TimeoutError, subprocess.SubprocessError):
        raise SystemExit('Isolated SMTP verification failed; no external mail or private diagnostics emitted') from None
