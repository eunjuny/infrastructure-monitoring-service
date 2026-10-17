#!/usr/bin/env python3
"""Stop/recover only the fixed isolated CI backend; never touch local/auth projects."""
import datetime
import json
import os
from pathlib import Path
import re
import sys
import subprocess
import time
import urllib.request

ROOT = Path(__file__).resolve().parents[1]
PROJECT = os.environ.get('CI_PROJECT_NAME', 'infrastructure-monitoring-ci')
if not re.fullmatch(r'infrastructure-monitoring-ci(?:-[a-z0-9]+)?', PROJECT) or PROJECT.endswith('-auth'):
    raise SystemExit('Recovery drill requires the isolated non-auth CI project')
ENV = {**os.environ, 'RUNTIME_WEB_PORT': '5185', 'RUNTIME_API_PORT': '8089',
       'RUNTIME_PROMETHEUS_PORT': '9093', 'RUNTIME_ALERTMANAGER_PORT': '9193'}
COMMAND = ['docker', 'compose', '-f', 'compose.runtime.yaml']
if os.environ.get('CI_IMMUTABLE_IMAGES') == '1':
    COMMAND += ['-f', 'compose.ci-images.yaml']
COMMAND += ['-p', PROJECT, '--profile', 'monitoring']


def compose(*args):
    return subprocess.check_output(COMMAND + list(args), cwd=ROOT, env=ENV, stderr=subprocess.DEVNULL, timeout=60)


def get(path):
    with urllib.request.urlopen('http://127.0.0.1:9093' + path, timeout=3) as response:
        return json.load(response)


def eventually(check, timeout=120):
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        try:
            if check():
                return
        except (OSError, ValueError, subprocess.SubprocessError):
            pass  # Bounded readiness retry; final failure is recorded below.
        time.sleep(2)
    raise TimeoutError('Recovery verification timed out')


def state():
    groups = get('/api/v1/rules?type=alert')['data']['groups']
    return next((rule['state'] for group in groups for rule in group['rules']
                 if rule['name'] == 'BackendScrapeUnavailable'), None)


def received(status, since):
    command = 'import json,urllib.request; print(urllib.request.urlopen("http://127.0.0.1:8090/events",timeout=3).read().decode())'
    events = json.loads(compose('exec', '-T', 'alert-receiver', 'python', '-c', command))['events']
    return any(event['name'] == 'BackendScrapeUnavailable' and event['status'] == status
               and datetime.datetime.fromisoformat(event['receivedAt']).timestamp() >= since for event in events)


def main():
    if sys.argv[1:] == ['--prepare']:
        output = ROOT / 'frontend/.validation/alerting'
        output.mkdir(parents=True, exist_ok=True)
        (output / 'summary.json').write_text('{"success": false, "completed": false}\n')
        return
    if sys.argv[1:]:
        raise ValueError('Unsupported drill options')
    steps = {}
    started = time.monotonic()
    since = time.time()
    stopped = False
    restored = False
    success = False
    try:
        # Verify project labels before the only disruptive action.
        identity = compose('ps', '-q', 'backend').decode().strip()
        label = subprocess.check_output(['docker', 'inspect', '--format', '{{index .Config.Labels "com.docker.compose.project"}}', identity], timeout=10).decode().strip()
        if label != PROJECT:
            raise ValueError('Unexpected Compose ownership')
        eventually(lambda: state() == 'inactive')
        stopped = True  # Restore even if stop times out after reaching the container.
        compose('stop', 'backend')
        eventually(lambda: state() == 'firing', 150)
        steps['firingSeconds'] = round(time.monotonic() - started, 2)
        eventually(lambda: received('firing', since), 90)
        steps['independentNotificationSeconds'] = round(time.monotonic() - started, 2)
        compose('up', '-d', '--no-deps', 'backend')
        restored = True
        eventually(lambda: state() == 'inactive', 120)
        eventually(lambda: received('resolved', since), 90)
        steps['resolvedNotificationSeconds'] = round(time.monotonic() - started, 2)
        with urllib.request.urlopen('http://127.0.0.1:5185/api/resources/summary', timeout=10) as response:
            assert json.load(response) == {'clusters': 20, 'nodes': 200, 'namespaces': 2000, 'pods': 150000}
        steps['dataPreserved'] = True
        success = True
    finally:
        if stopped and not restored:
            compose('up', '-d', '--no-deps', 'backend')
        output = ROOT / 'frontend/.validation/alerting'
        output.mkdir(parents=True, exist_ok=True)
        report = {'success': success, 'steps': steps, 'scope': 'isolated-ci-backend-only', 'smtpUsed': False}
        (output / 'summary.json').write_text(json.dumps(report, indent=2) + '\n')
        print('Alert recovery drill:', json.dumps(report))
    if not success:
        raise SystemExit(1)


if __name__ == '__main__':
    try:
        main()
    except (OSError, ValueError, AssertionError, TimeoutError, subprocess.SubprocessError):
        raise SystemExit('Alert recovery drill failed; inspect the sanitized summary and isolated service status')
