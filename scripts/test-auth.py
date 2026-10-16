#!/usr/bin/env python3
"""Real OIDC checks: never retain reporter call logs, credentials or tokens."""
import argparse
import json
import os
from pathlib import Path
import re
import subprocess
import sys
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[1]

def redact(output, credentials):
    for user in credentials['users'].values():
        output = output.replace(user['password'], '[REDACTED]')
    output = re.sub(r'eyJ[A-Za-z0-9_-]+\.[A-Za-z0-9_-]+\.[A-Za-z0-9_-]+', '[REDACTED_JWT]', output)
    return re.sub(r'([?&](?:code|state|session_state|code_verifier|id_token_hint)=)[^&\s"<>]+', r'\1[REDACTED]', output)


def cases(suites):
    for suite in suites:
        for spec in suite.get('specs', []):
            for test in spec.get('tests', []):
                yield spec['title'], test
        yield from cases(suite.get('suites', []))


def junit(report):
    suite = ET.Element('testsuite', name='Real Keycloak authentication')
    count = failures = skipped = 0
    for title, test in cases(report.get('suites', [])):
        count += 1
        result = test.get('results', [{}])[-1]
        case = ET.SubElement(suite, 'testcase', name=title, classname='KeycloakBrowser',
                             time=str(result.get('duration', 0) / 1000))
        status = result.get('status')
        if status == 'skipped':
            skipped += 1
            ET.SubElement(case, 'skipped')
        elif status != 'passed':
            failures += 1
            ET.SubElement(case, 'failure', message='Authentication assertion failed; sensitive diagnostics omitted')
    suite.set('tests', str(count))
    suite.set('failures', str(failures))
    suite.set('skipped', str(skipped))
    return ET.tostring(suite, encoding='unicode') + '\n'


def main():
    parser = argparse.ArgumentParser()
    mode = parser.add_mutually_exclusive_group()
    mode.add_argument('--performance', action='store_true')
    mode.add_argument('--ci', action='store_true')
    mode.add_argument('--prepare-ci', action='store_true')
    args = parser.parse_args()
    if args.prepare_ci:
        output = ROOT / 'frontend/.validation/auth-ci'
        output.mkdir(parents=True, exist_ok=True)
        (output / 'junit.xml').write_text('<testsuite name="Authentication setup" tests="1" failures="1"><testcase name="CI authentication completed"><failure message="CI authentication has not completed"/></testcase></testsuite>\n')
        (output / 'summary.json').write_text('{"completed": false}\n')
        return 0
    private = ROOT / ('.local/auth-ci' if args.ci else '.local/auth') / 'credentials.json'
    if not private.exists():
        raise SystemExit('Provision the matching authentication environment first')
    credentials = json.loads(private.read_text())
    environment = {**os.environ, 'E2E_AUTH': '1', 'E2E_AUTH_CI': '1' if args.ci else '0',
                   'E2E_BASE_URL': 'http://127.0.0.1:5186' if args.ci else 'http://127.0.0.1:5176',
                   'E2E_KEYCLOAK_URL': 'http://127.0.0.1:8193' if args.ci else 'http://127.0.0.1:8183'}
    command = ['node', 'scripts/measure-auth-performance.mjs'] if args.performance else ['npx', 'playwright', 'test', '--reporter=json']
    result = subprocess.run(command, cwd=ROOT / 'frontend', env=environment, capture_output=True, text=True)
    if args.performance:
        print(redact(result.stdout + '\n' + result.stderr, credentials))
        return result.returncode
    try:
        report = json.loads(result.stdout)
        stats = report['stats']
        if args.ci:
            output = ROOT / 'frontend/.validation/auth-ci'
            output.mkdir(parents=True, exist_ok=True)
            (output / 'junit.xml').write_text(junit(report))
            (output / 'summary.json').write_text(json.dumps(stats, indent=2) + '\n')
        print('Real Keycloak E2E:', stats)
        if result.returncode:
            for title, test in cases(report.get('suites', [])):
                if test.get('results', [{}])[-1].get('status') != 'passed':
                    print('Failed authentication check:', redact(title, credentials))
            print('Sensitive call logs/response bodies were not retained.')
        if not stats.get('expected') or stats.get('skipped') or stats.get('unexpected') or stats.get('flaky'):
            return 1
    except (json.JSONDecodeError, KeyError):
        print('Authentication runner failed; raw output suppressed to protect credentials.')
        return 1
    return result.returncode


if __name__ == '__main__':
    sys.exit(main())
