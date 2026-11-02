#!/usr/bin/env python3
"""Install a per-user macOS LaunchAgent; never expose Jenkins publicly."""
import os
from pathlib import Path
import plistlib
import subprocess
import sys


ROOT = Path(__file__).resolve().parents[1]
subprocess.run([sys.executable, str(ROOT / 'scripts/jenkins-local.py'), '--prepare-only', '--build-controller'],
               cwd=ROOT, check=True)
local = ROOT / '.local/dev-cicd'
local.mkdir(parents=True, exist_ok=True, mode=0o700)
label = 'dev.infrastructure.monitoring.cicd'
path = Path.home() / 'Library/LaunchAgents' / (label + '.plist')
path.parent.mkdir(parents=True, exist_ok=True)
for name in ['stdout.log', 'stderr.log']:
    descriptor = os.open(local / name, os.O_CREAT | os.O_APPEND | os.O_WRONLY, 0o600)
    os.close(descriptor)
configuration = {'Label': label, 'ProgramArguments': [sys.executable, str(ROOT / 'scripts/watch-dev.py')],
                 'WorkingDirectory': str(ROOT), 'StartInterval': 60, 'RunAtLoad': True,
                 'EnvironmentVariables': {'PATH': os.environ['PATH'], 'HOME': str(Path.home())},
                 'StandardOutPath': str(local / 'stdout.log'), 'StandardErrorPath': str(local / 'stderr.log')}
if os.environ.get('JAVA_HOME'):
    configuration['EnvironmentVariables']['JAVA_HOME'] = os.environ['JAVA_HOME']
path.write_bytes(plistlib.dumps(configuration))
path.chmod(0o600)
domain = 'gui/' + str(os.getuid())
subprocess.run(['launchctl', 'bootout', domain + '/' + label], stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
subprocess.run(['launchctl', 'bootstrap', domain, str(path)], check=True)
print('Dev polling installed: 60-second interval; requires user login, network and Docker Desktop.')
