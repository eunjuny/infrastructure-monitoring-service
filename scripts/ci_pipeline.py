#!/usr/bin/env python3
"""Commit-pinned CI stages for Jenkins and clean local verification checkouts."""
import argparse
from contextlib import contextmanager
import datetime
import json
import os
from pathlib import Path
import re
import subprocess
import sys
from tempfile import TemporaryDirectory
import time
import urllib.request

ROOT = Path(__file__).resolve().parents[1]
REPORT = ROOT / 'frontend/.validation/ci/provenance.json'
STAGES = ['quality', 'backend', 'frontend', 'images', 'browser', 'recovery', 'authentication', 'verify']
PROJECT = 'infrastructure-monitoring-ci'
IMAGE_KEYS = {'backend': 'CI_BACKEND_IMAGE', 'frontend': 'CI_FRONTEND_IMAGE',
              'authFrontend': 'CI_AUTH_FRONTEND_IMAGE', 'receiver': 'CI_RECEIVER_IMAGE'}


def execute(command, env=None, cwd=ROOT, capture=False):
    return subprocess.run(command, cwd=cwd, env=env, check=True, text=True,
                          stdout=subprocess.PIPE if capture else None).stdout


def git(*args, cwd=ROOT):
    return execute(['git', *args], cwd=cwd, capture=True).strip()


def identity(root=ROOT):
    revision = git('rev-parse', '--verify', 'HEAD^{commit}', cwd=root)
    tree = git('rev-parse', 'HEAD^{tree}', cwd=root)
    if git('status', '--porcelain', '--untracked-files=normal', cwd=root):
        raise ValueError('CI requires a clean committed checkout; working files are not a source revision')
    return revision, tree


def prepare(root=ROOT, engine=None, build_id=None, expected=None):
    revision, tree = identity(root)
    expected = expected or os.environ.get('CI_COMMIT_SHA') or revision
    if not re.fullmatch(r'[0-9a-f]{40}', expected) or revision != expected:
        raise ValueError('Checked out revision does not match the requested full commit SHA')
    engine = engine or os.environ.get('CI_ENGINE', 'local')
    build_id = build_id or os.environ.get('CI_BUILD_ID') or str(time.time_ns())
    if engine not in {'jenkins', 'local'} or not re.fullmatch(r'[A-Za-z0-9_.-]{1,40}', build_id):
        raise ValueError('Invalid CI engine or build identifier')
    tag = f'{revision[:12]}-{engine}-{build_id}'
    return {'schemaVersion': 1, 'commit': revision, 'tree': tree, 'engine': engine, 'buildId': build_id,
            'imageTag': tag, 'preparedAt': datetime.datetime.now(datetime.timezone.utc).isoformat(),
            'registryAuthentication': 'public-anonymous-isolated-client',
            'images': {}, 'stages': {}, 'complete': False,
            'imageReferences': {key: f'infrastructure-monitoring-{key.lower()}:{tag}' for key in IMAGE_KEYS}}


def save(report):
    REPORT.parent.mkdir(parents=True, exist_ok=True)
    REPORT.write_text(json.dumps(report, indent=2) + '\n')


def load():
    report = json.loads(REPORT.read_text())
    if not re.fullmatch(r'[0-9a-f]{40}', report.get('commit', '')):
        raise ValueError('Invalid provenance')
    return report


def environment(report):
    # Fixed isolated projects and ports; never inherited developer targets.
    values = {**os.environ, 'CI_COMMIT_SHA': report['commit'], 'CI_BUILD_ID': report['buildId'],
              'CI_ENGINE': report['engine'], 'CI_IMMUTABLE_IMAGES': '1', 'CI_PROJECT_NAME': PROJECT,
              'RUNTIME_WEB_PORT': '5185', 'RUNTIME_API_PORT': '8089',
              'RUNTIME_PROMETHEUS_PORT': '9093', 'RUNTIME_ALERTMANAGER_PORT': '9193'}
    values.update({IMAGE_KEYS[key]: value for key, value in report['imageReferences'].items()})
    return values


@contextmanager
def public_registry_environment(env):
    """Use public images without reading/writing the developer credential store."""
    if any(env.get(name) for name in ['DOCKER_TLS', 'DOCKER_TLS_VERIFY', 'DOCKER_CERT_PATH']):
        raise ValueError('Public-image CI requires a local Unix Docker endpoint, not TLS overrides')
    try:
        endpoint = env.get('DOCKER_HOST') if not env.get('DOCKER_CONTEXT') else None
        if not endpoint:
            endpoint = subprocess.check_output(
                ['docker', 'context', 'inspect', '--format', '{{.Endpoints.docker.Host}}'],
                env=env, text=True, stderr=subprocess.DEVNULL, timeout=10).strip()
        if not endpoint.startswith('unix:///'):
            raise ValueError('Public-image CI requires the existing local Unix Docker endpoint')
        metadata = json.loads(subprocess.check_output(
            ['docker', 'info', '--format',
             '{"plugins":{{json .ClientInfo.Plugins}},"operatingSystem":{{json .OperatingSystem}}}'],
            env=env, text=True, stderr=subprocess.DEVNULL, timeout=10))
        plugins = {}
        for plugin in metadata['plugins']:
            if plugin.get('Name') not in {'buildx', 'compose'}:
                continue
            path = Path(plugin.get('Path', ''))
            if not path.is_absolute() or not path.is_file() or not os.access(path, os.X_OK):
                raise ValueError('Installed Docker CI plugin is unavailable')
            plugins[plugin['Name']] = path.resolve()
        if set(plugins) != {'buildx', 'compose'}:
            raise ValueError('Existing Buildx and Compose plugins are required')
    except (OSError, subprocess.SubprocessError, TypeError, AttributeError, KeyError):
        raise ValueError('Could not discover the existing local Docker client safely') from None
    with TemporaryDirectory(prefix='infrastructure-ci-docker-') as temporary:
        directory = Path(temporary)
        directory.chmod(0o700)
        plugin_directory = directory / 'cli-plugins'
        plugin_directory.mkdir(mode=0o700)
        for name, executable in plugins.items():
            (plugin_directory / ('docker-' + name)).symlink_to(executable)
        config = directory / 'config.json'
        descriptor = os.open(config, os.O_CREAT | os.O_EXCL | os.O_WRONLY, 0o600)
        with os.fdopen(descriptor, 'w') as output:
            # An explicit anonymous Hub entry also avoids auto-selecting an OS helper.
            json.dump({'auths': {'https://index.docker.io/v1/': {}}}, output)
            output.write('\n')
        isolated = {**env, 'DOCKER_CONFIG': str(directory), 'DOCKER_HOST': endpoint,
                    'BUILDX_CONFIG': str(directory / 'buildx')}
        for name in ['DOCKER_CONTEXT', 'DOCKER_AUTH_CONFIG', 'BUILDX_BUILDER', 'BUILDKIT_HOST']:
            isolated.pop(name, None)
        if sys.platform == 'darwin' and metadata.get('operatingSystem') == 'Docker Desktop':
            # The host client socket is not a mountable socket inside Desktop's VM.
            isolated.setdefault('TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE', '/var/run/docker.sock')
        yield isolated


def runtime(*args):
    return ['docker', 'compose', '-f', 'compose.runtime.yaml', '-f', 'compose.ci-images.yaml',
            '-p', PROJECT, '--profile', 'monitoring', *args]


def inspect_image(reference, report, env=None):
    image = json.loads(execute(['docker', 'image', 'inspect', reference], env, capture=True))[0]
    labels = image['Config'].get('Labels') or {}
    if labels.get('org.opencontainers.image.revision') != report['commit'] or labels.get('io.infrastructure.build-id') != report['buildId']:
        raise ValueError('Image provenance does not match this build')
    return {'reference': reference, 'id': image['Id'], 'revision': report['commit'], 'buildId': report['buildId']}


def attest(report, auth=False, env=None):
    project = 'infrastructure-monitoring-ci-auth' if auth else PROJECT
    expected = {'backend': 'backend', 'frontend': 'authFrontend' if auth else 'frontend', 'alert-receiver': 'receiver'}
    for service, key in expected.items():
        ids = execute(['docker', 'ps', '-q', '--filter', f'label=com.docker.compose.project={project}',
                       '--filter', f'label=com.docker.compose.service={service}'], env, capture=True).split()
        if len(ids) != 1:
            raise ValueError('Expected exactly one isolated service container')
        container = json.loads(execute(['docker', 'inspect', ids[0]], env, capture=True))[0]
        if container['Image'] != report['images'][key]['id']:
            raise ValueError('Runtime container is not the image built by this commit/build')
    report['authRuntimeAttested' if auth else 'runtimeAttested'] = True


def get_json(url):
    with urllib.request.urlopen(url, timeout=5) as response:
        return json.load(response)


def eventually(check, timeout=150):
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        try:
            if check():
                return
        except (OSError, ValueError, KeyError):
            pass
        time.sleep(2)
    raise TimeoutError('Isolated runtime readiness failed')


def perform(stage, report, env):
    with public_registry_environment(env) as isolated:
        perform_isolated(stage, report, isolated)


def perform_isolated(stage, report, env):
    if stage == 'quality':
        execute(['bash', 'scripts/check.sh', 'fast'], env)
        execute(['bash', 'scripts/test-alert-rules.sh'], env)
    elif stage == 'backend':
        execute(['./backend/gradlew', '-p', 'backend', '--no-daemon', '--console=plain', 'clean', 'build'], env)
    elif stage == 'frontend':
        for command in [['npm', 'ci'], ['npm', 'test'], ['npm', 'run', 'build'], ['npm', 'audit', '--audit-level=moderate']]:
            execute(command, env, ROOT / 'frontend')
    elif stage == 'images':
        execute(runtime('build', 'backend', 'frontend', 'alert-receiver'), env)
        execute(['docker', 'build', '-f', 'frontend/Dockerfile', '--build-arg', 'VITE_AUTH_ENABLED=true',
                 '--build-arg', 'SOURCE_REVISION=' + report['commit'], '--build-arg', 'BUILD_ID=' + report['buildId'],
                 '-t', report['imageReferences']['authFrontend'], '.'], env)
        report['images'] = {key: inspect_image(reference, report, env) for key, reference in report['imageReferences'].items()}
    elif stage == 'browser':
        started = time.time()
        execute(runtime('up', '-d', '--no-build', '--pull', 'never'), env)
        eventually(lambda: get_json('http://127.0.0.1:5185/api/resources/summary') ==
                   {'clusters': 20, 'nodes': 200, 'namespaces': 2000, 'pods': 150000})
        def initial_batch():
            rows = get_json('http://127.0.0.1:5185/api/resources/NODE/1/events')['items']
            return bool(rows) and datetime.datetime.fromisoformat(rows[0]['occurredAt'].replace('Z', '+00:00')).timestamp() >= started
        eventually(initial_batch, 60)
        attest(report, env=env)
        execute(['npm', 'run', 'test:e2e'], {**env, 'E2E_MONITORING': '1', 'E2E_BASE_URL': 'http://127.0.0.1:5185',
                'TEST_COMPOSE_FILE': 'compose.runtime.yaml', 'TEST_COMPOSE_PROJECT': PROJECT}, ROOT / 'frontend')
        eventually(lambda: any(row['value'][1] == '1' for row in
                   get_json('http://127.0.0.1:9093/api/v1/query?query=up')['data']['result']))
    elif stage == 'recovery':
        execute(['python3', 'scripts/test-alerting.py'], env)
        attest(report, env=env)
    elif stage == 'authentication':
        execute(['bash', 'scripts/ci-auth.sh'], env)
        refreshed = load()  # Auth stage records attestation before its containers are removed.
        if not refreshed.get('authRuntimeAttested'):
            raise ValueError('Authentication runtime image provenance was not verified')
        report['authRuntimeAttested'] = True
    elif stage == 'verify':
        for key, reference in report['imageReferences'].items():
            if inspect_image(reference, report, env)['id'] != report['images'][key]['id']:
                raise ValueError('Image tag changed during this build')
        if not report.get('runtimeAttested') or not report.get('authRuntimeAttested'):
            raise ValueError('Runtime provenance is incomplete')
        report['complete'] = True


def cleanup(report):
    failures = []
    with public_registry_environment(environment(report)) as env:
        for command in [['python3', 'scripts/auth-local.py', '--ci', '--stop'], runtime('down', '--remove-orphans')]:
            try:
                execute(command, env)
            except subprocess.CalledProcessError:
                failures.append('isolated-runtime-cleanup')
    report['cleanup'] = 'failed' if failures else 'passed'
    save(report)
    if failures:
        raise ValueError('Isolated cleanup did not complete')


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('stage', choices=['prepare', *STAGES, 'attest-auth', 'cleanup'])
    args = parser.parse_args()
    if args.stage == 'prepare':
        if REPORT.exists():
            raise ValueError('Use a fresh checkout for each CI run')
        report = prepare()
        save(report)
        execute(['python3', 'scripts/test-auth.py', '--prepare-ci'])
        execute(['python3', 'scripts/test-alerting.py', '--prepare'])
        print('Pinned source:', report['commit'], 'build:', report['engine'], report['buildId'])
        return
    if not REPORT.exists() and args.stage == 'cleanup':
        return
    report = load()
    if args.stage == 'cleanup':
        cleanup(report)
        return
    if identity() != (report['commit'], report['tree']):
        raise ValueError('Source revision or tree changed during CI')
    if args.stage == 'attest-auth':
        attest(report, auth=True)
        save(report)
        return
    index = STAGES.index(args.stage)
    if any(report['stages'].get(stage, {}).get('result') != 'passed' for stage in STAGES[:index]):
        raise ValueError('Required preceding CI stages have not passed')
    started = time.monotonic()
    report['stages'][args.stage] = {'result': 'running'}
    save(report)
    try:
        perform(args.stage, report, environment(report))
        if identity() != (report['commit'], report['tree']):
            raise ValueError('Source changed while the stage was running')
        report['stages'][args.stage] = {'result': 'passed', 'seconds': round(time.monotonic() - started, 3)}
    except Exception:
        report['complete'] = False
        report['stages'][args.stage] = {'result': 'failed', 'seconds': round(time.monotonic() - started, 3)}
        raise
    finally:
        save(report)


if __name__ == '__main__':
    try:
        main()
    except (OSError, ValueError, TimeoutError, subprocess.SubprocessError, KeyError) as error:
        # Never dump a command environment or an external response body.
        print('CI stage failed:', str(error) if isinstance(error, ValueError) else type(error).__name__, file=sys.stderr)
        sys.exit(1)
