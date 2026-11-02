#!/usr/bin/env python3
"""Deploy verified local CI images to the existing authenticated Compose project."""
import argparse
import fcntl
import json
import os
from pathlib import Path
import subprocess
import sys
import time
import urllib.request

from ci_pipeline import IMAGE_KEYS, STAGES, inspect_image, public_registry_environment

SERVICES = {'backend': 'backend', 'frontend': 'authFrontend', 'alert-receiver': 'receiver'}
PROJECT = 'infrastructure-monitoring-auth'


def validate(report):
    if not report.get('complete') or not report.get('authRuntimeAttested'):
        raise ValueError('Verified authenticated CI manifest required')
    if any(report.get('stages', {}).get(stage, {}).get('result') != 'passed' for stage in STAGES):
        raise ValueError('All CI stages must pass before deployment')
    if set(report.get('images', {})) != set(IMAGE_KEYS):
        raise ValueError('Incomplete image manifest')


def private_json(path, value):
    temporary = path.with_suffix('.tmp')
    descriptor = os.open(temporary, os.O_WRONLY | os.O_CREAT | os.O_TRUNC, 0o600)
    with os.fdopen(descriptor, 'w') as output:
        json.dump(value, output, indent=2)
        output.write('\n')
    temporary.replace(path)


def deploy(report, target):
    validate(report)
    target = target.resolve()
    remote = subprocess.check_output(['git', '-C', str(target), 'ls-remote', '--exit-code', 'origin',
                                      'refs/heads/dev'], text=True, timeout=40).split()[0]
    if remote != report['commit']:
        raise ValueError('Build superseded by a newer remote dev commit; deployment blocked')
    directory = target / '.local/deployment'
    directory.mkdir(parents=True, exist_ok=True, mode=0o700)
    if not (target / '.local/auth/compose.env').is_file():
        raise ValueError('Existing authenticated environment required; initialize it first')
    with (directory / 'deploy.lock').open('w') as lock:
        fcntl.flock(lock, fcntl.LOCK_EX | fcntl.LOCK_NB)
        with public_registry_environment(dict(os.environ)) as env:
            def run(args, capture=True):
                return subprocess.run(args, cwd=target, env=env, check=True, text=True,
                                      stdout=subprocess.PIPE if capture else subprocess.DEVNULL,
                                      stderr=subprocess.PIPE).stdout

            def container(service):
                ids = run(['docker', 'ps', '-aq', '--filter', 'label=com.docker.compose.project=' + PROJECT,
                           '--filter', 'label=com.docker.compose.service=' + service]).split()
                if len(ids) != 1:
                    raise ValueError('Expected one existing service: ' + service)
                return json.loads(run(['docker', 'inspect', ids[0]]))[0]

            for key in IMAGE_KEYS:
                if inspect_image(report['imageReferences'][key], report, env)['id'] != report['images'][key]['id']:
                    raise ValueError('Image tag changed after CI validation')
            previous = {service: container(service)['Image'] for service in SERVICES}
            database = container('db')
            mounts = database['Mounts']
            identity_container = container('keycloak')['Id']
            def resource_counts():
                return run(['docker', 'exec', database['Id'], 'psql', '-U', 'infrastructure_admin',
                            '-d', 'monitoring', '-At', '-c',
                            'SELECT (SELECT count(*) FROM infrastructure_cluster), '
                            '(SELECT count(*) FROM infrastructure_node), '
                            '(SELECT count(*) FROM infrastructure_namespace), '
                            '(SELECT count(*) FROM infrastructure_pod)']).strip()
            original_counts = resource_counts()
            before = {'commit': report['commit'], 'buildId': report['buildId'], 'previous': previous,
                      'images': {service: report['images'][key]['id'] for service, key in SERVICES.items()}}
            private_json(directory / 'pending.json', before)
            # Save a database backup before Flyway can run. Never print dump contents.
            backup = directory / ('monitoring-' + report['imageTag'] + '.dump')
            with os.fdopen(os.open(backup, os.O_WRONLY | os.O_CREAT | os.O_TRUNC, 0o600), 'wb') as output:
                subprocess.run(['docker', 'exec', database['Id'], 'pg_dump', '-U', 'infrastructure_admin',
                                '-d', 'monitoring', '-Fc'], env=env, stdout=output, stderr=subprocess.PIPE, check=True)
            override = directory / 'images.json'
            base = ['docker', 'compose', '--env-file', str(target / '.local/auth/compose.env'),
                    '--project-directory', str(target), '-p', PROJECT, '-f', str(target / 'compose.auth.yaml'),
                    '-f', str(override)]

            def apply(images):
                private_json(override, {'services': {service: {'image': image, 'pull_policy': 'never'}
                                                      for service, image in images.items()}})
                run(base + ['up', '-d', '--no-deps', '--no-build', '--pull', 'never', *SERVICES])

            def healthy():
                deadline = time.monotonic() + 180
                while time.monotonic() < deadline:
                    try:
                        backend = container('backend')
                        if backend['State'].get('Health', {}).get('Status') == 'healthy':
                            frontend = container('frontend')
                            port = frontend['NetworkSettings']['Ports']['8080/tcp'][0]['HostPort']
                            with urllib.request.urlopen('http://127.0.0.1:' + port + '/api/auth/config', timeout=5) as response:
                                if json.load(response).get('enabled') is True:
                                    return
                    except (OSError, ValueError, KeyError, subprocess.SubprocessError):
                        pass
                    time.sleep(3)
                raise TimeoutError('Deployment readiness failed')

            try:
                apply(before['images'])
                healthy()
                if any(container(service)['Image'] != image for service, image in before['images'].items()):
                    raise ValueError('Running images differ from verified images')
                if container('db')['Id'] != database['Id'] or container('db')['Mounts'] != mounts:
                    raise ValueError('Database container or volumes changed')
                if container('keycloak')['Id'] != identity_container or resource_counts() != original_counts:
                    raise ValueError('Identity service or resource counts changed')
                private_json(directory / 'current.json', {**before, 'result': 'deployed', 'backup': backup.name,
                                                        'resourceCounts': original_counts})
            except Exception:
                # Image rollback does not undo a database migration. Preserve backup for manual recovery.
                try:
                    apply(previous)
                    healthy()
                    status = 'images-restored-database-review-required'
                except Exception:
                    status = 'rollback-failed-manual-recovery-required'
                private_json(directory / 'failed.json', {**before, 'result': status, 'backup': backup.name})
                raise ValueError(status) from None
            print('Local deployment verified for commit', report['commit'])


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--manifest', type=Path, required=True)
    parser.add_argument('--target', type=Path, required=True)
    args = parser.parse_args()
    deploy(json.loads(args.manifest.read_text()), args.target)


if __name__ == '__main__':
    try:
        main()
    except (OSError, ValueError, TimeoutError, subprocess.SubprocessError) as error:
        print('Local deployment failed:', str(error) if isinstance(error, ValueError) else type(error).__name__, file=sys.stderr)
        sys.exit(1)
