#!/usr/bin/env python3
"""Loopback-only push detection: poll origin/dev and dispatch the committed Jenkins pipeline."""
import argparse
import fcntl
import json
import os
from pathlib import Path
import re
import subprocess
import sys
import time

ROOT = Path(__file__).resolve().parents[1]


def remote_revision(repository):
    result = subprocess.check_output(['git', '-C', str(repository), 'ls-remote', '--exit-code', 'origin',
                                      'refs/heads/dev'], text=True, timeout=40).split()
    if len(result) != 2 or not re.fullmatch('[0-9a-f]{40}', result[0]):
        raise ValueError('Remote dev commit unavailable')
    return result[0]


def main():
    local = ROOT / '.local/dev-cicd'
    local.mkdir(parents=True, exist_ok=True, mode=0o700)
    with (local / 'watch.lock').open('w') as lock:
        try:
            fcntl.flock(lock, fcntl.LOCK_EX | fcntl.LOCK_NB)
        except BlockingIOError:
            return
        subprocess.run([sys.executable, str(ROOT / 'scripts/jenkins-local.py'), '--prepare-only'],
                       cwd=ROOT, check=True)
        sha = remote_revision(ROOT)
        state_file = local / 'state.json'
        state = json.loads(state_file.read_text()) if state_file.exists() else {}
        if state.get('deployed') == sha:
            return
        if state.get('attempted') == sha and time.time() - state.get('attemptedAt', 0) < 600:
            return
        mirror = local / 'source.git'
        if not mirror.exists():
            subprocess.run(['git', 'init', '--bare', str(mirror)], check=True, stdout=subprocess.DEVNULL)
        remote = subprocess.check_output(['git', '-C', str(ROOT), 'remote', 'get-url', 'origin'], text=True).strip()
        subprocess.run(['git', '-C', str(mirror), 'fetch', '--no-tags', remote,
                        '+refs/heads/dev:refs/heads/dev'], check=True, stdout=subprocess.DEVNULL, timeout=90)
        if subprocess.check_output(['git', '-C', str(mirror), 'rev-parse', 'refs/heads/dev'], text=True).strip() != sha:
            return  # New push during fetch; next poll uses the newest commit.
        state.update(attempted=sha, attemptedAt=time.time())
        state_file.write_text(json.dumps(state) + '\n')
        result = subprocess.run([sys.executable, str(ROOT / 'scripts/jenkins-local.py'), '--repository',
                                 str(mirror), '--revision', sha, '--deploy-local'], cwd=ROOT)
        state['attemptedAt'] = time.time()
        state['result'] = 'passed' if result.returncode == 0 else 'failed'
        if result.returncode == 0:
            state['deployed'] = sha
        state_file.write_text(json.dumps(state) + '\n')
        if result.returncode:
            raise ValueError('Jenkins dev CI/CD failed; retry after ten minutes')


if __name__ == '__main__':
    try:
        main()
    except (OSError, ValueError, subprocess.SubprocessError) as error:
        print('Dev watcher:', str(error) if isinstance(error, ValueError) else type(error).__name__, file=sys.stderr)
        sys.exit(1)
