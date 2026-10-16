"""Offline regression checks: immutable checkout, stage ordering and image identity."""
import importlib.util
import json
import os
from pathlib import Path
import subprocess
import sys
from tempfile import TemporaryDirectory
import unittest
from unittest.mock import patch

ROOT = Path(__file__).resolve().parents[2]
SPEC = importlib.util.spec_from_file_location('ci_pipeline', ROOT / 'scripts/ci_pipeline.py')
CI = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(CI)


class CiProvenanceTest(unittest.TestCase):
    def setUp(self):
        self.temporary = TemporaryDirectory(prefix='monitoring-ci-test-')
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name)
        self.repo = self.root / 'repository'
        self.repo.mkdir()
        self.git('init', '-q')
        self.git('config', 'user.name', 'CI fixture')
        self.git('config', 'user.email', 'fixture@example.invalid')
        (self.repo / '.gitignore').write_text('.validation/\n')
        (self.repo / 'source.txt').write_text('committed\n')
        self.git('add', '.')
        self.git('commit', '-qm', 'fixture')
        self.sha = self.git('rev-parse', 'HEAD')
        self.tree = self.git('rev-parse', 'HEAD^{tree}')
        self.report = CI.prepare(self.repo, engine='jenkins', build_id='12', expected=self.sha)

    def git(self, *args):
        return subprocess.check_output(['git', '-c', 'core.hooksPath=/dev/null', *args],
                                       cwd=self.repo, text=True, stderr=subprocess.DEVNULL).strip()

    def test_metadata_binds_commit_tree_build_and_unique_image_references(self):
        self.assertEqual((self.sha, self.tree), (self.report['commit'], self.report['tree']))
        self.assertEqual(4, len(set(self.report['imageReferences'].values())))
        self.assertTrue(all(value.endswith(f'{self.sha[:12]}-jenkins-12')
                            for value in self.report['imageReferences'].values()))
        other = CI.prepare(self.repo, engine='jenkins', build_id='13', expected=self.sha)
        self.assertNotEqual(other['imageTag'], self.report['imageTag'])

    def test_dirty_tracked_and_untracked_source_are_rejected(self):
        (self.repo / 'source.txt').write_text('not committed\n')
        with self.assertRaisesRegex(ValueError, 'clean committed'):
            CI.identity(self.repo)
        (self.repo / 'source.txt').write_text('committed\n')
        (self.repo / 'new.txt').write_text('not committed\n')
        with self.assertRaisesRegex(ValueError, 'clean committed'):
            CI.identity(self.repo)

    def test_jenkins_is_the_only_ci_engine_and_local_verification_remains_available(self):
        self.assertEqual('local', CI.prepare(self.repo, engine='local', build_id='12', expected=self.sha)['engine'])
        for engine in ['github', 'unknown']:
            with self.subTest(engine=engine), self.assertRaisesRegex(ValueError, 'Invalid CI engine'):
                CI.prepare(self.repo, engine=engine, build_id='12', expected=self.sha)
        self.assertTrue((ROOT / 'Jenkinsfile').is_file())
        self.assertFalse((ROOT / '.github/workflows/verify.yaml').exists())

    def test_ignored_reports_do_not_change_source_identity(self):
        output = self.repo / '.validation'
        output.mkdir()
        (output / 'report.json').write_text('{}')
        self.assertEqual((self.sha, self.tree), CI.identity(self.repo))

    def test_requested_revision_and_build_identifier_are_validated(self):
        for expected in ['abc123', '0' * 40]:
            with self.subTest(expected=expected), self.assertRaises(ValueError):
                CI.prepare(self.repo, engine='local', build_id='12', expected=expected)
        with self.assertRaises(ValueError):
            CI.prepare(self.repo, engine='jenkins', build_id='../unsafe', expected=self.sha)

    def test_fresh_clone_uses_pinned_commit_not_later_source_or_working_files(self):
        (self.repo / 'source.txt').write_text('later commit\n')
        self.git('add', '.')
        self.git('commit', '-qm', 'later fixture')
        (self.repo / 'source.txt').write_text('mutable working files\n')
        destination = self.root / 'checkout'
        subprocess.run(['git', '-c', 'core.hooksPath=/dev/null', 'clone', '--no-local',
                        '--no-checkout', str(self.repo), str(destination)], check=True,
                       stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
        subprocess.run(['git', '-c', 'core.hooksPath=/dev/null', 'checkout', '--detach', self.sha],
                       cwd=destination, check=True, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
        self.assertEqual('committed\n', (destination / 'source.txt').read_text())
        self.assertEqual((self.sha, self.tree), CI.identity(destination))
        self.assertFalse((destination / '.git/objects/info/alternates').exists())

    def test_environment_cannot_redirect_runtime_to_developer_project(self):
        with patch.dict(os.environ, {'CI_PROJECT_NAME': 'developer', 'RUNTIME_WEB_PORT': '5174'}):
            environment = CI.environment(self.report)
        self.assertEqual('infrastructure-monitoring-ci', environment['CI_PROJECT_NAME'])
        self.assertEqual('5185', environment['RUNTIME_WEB_PORT'])
        self.assertEqual(self.report['imageReferences']['authFrontend'], environment['CI_AUTH_FRONTEND_IMAGE'])
        self.assertIn('compose.ci-images.yaml', CI.runtime('up'))

    def installed_docker_metadata(self):
        plugins = []
        for name in ['buildx', 'compose']:
            path = self.root / ('docker-' + name)
            path.write_text('fixture executable\n')
            path.chmod(0o700)
            plugins.append({'Name': name, 'Path': str(path)})
        return json.dumps({'plugins': plugins, 'operatingSystem': 'Docker Desktop'})

    def test_public_registry_client_preserves_developer_config_and_scopes_local_endpoint(self):
        metadata = self.installed_docker_metadata()
        developer = self.root / 'developer-docker'
        developer.mkdir()
        original = '{"credsStore":"desktop","auths":{"private.example.invalid":{}}}\n'
        (developer / 'config.json').write_text(original)
        env = {**os.environ, 'DOCKER_CONFIG': str(developer), 'DOCKER_CONTEXT': 'desktop-linux',
               'DOCKER_AUTH_CONFIG': 'not-for-ci', 'BUILDX_CONFIG': '/developer-buildx',
               'BUILDX_BUILDER': 'developer-builder', 'BUILDKIT_HOST': 'tcp://unrelated:1234'}
        for name in ['DOCKER_TLS', 'DOCKER_TLS_VERIFY', 'DOCKER_CERT_PATH']:
            env.pop(name, None)
        before = dict(env)
        with patch.object(CI.subprocess, 'check_output', side_effect=['unix:///fixture/docker.sock', metadata]) as discover:
            with CI.public_registry_environment(env) as isolated:
                temporary = Path(isolated['DOCKER_CONFIG'])
                self.assertNotEqual(developer, temporary)
                self.assertEqual({'auths': {'https://index.docker.io/v1/': {}}},
                                 json.loads((temporary / 'config.json').read_text()))
                self.assertEqual(0o700, temporary.stat().st_mode & 0o777)
                self.assertEqual(0o600, (temporary / 'config.json').stat().st_mode & 0o777)
                self.assertEqual('unix:///fixture/docker.sock', isolated['DOCKER_HOST'])
                self.assertEqual(str(temporary / 'buildx'), isolated['BUILDX_CONFIG'])
                for name in ['DOCKER_CONTEXT', 'DOCKER_AUTH_CONFIG', 'BUILDX_BUILDER', 'BUILDKIT_HOST']:
                    self.assertNotIn(name, isolated)
                for name in ['buildx', 'compose']:
                    self.assertEqual((self.root / ('docker-' + name)).resolve(),
                                     (temporary / 'cli-plugins' / ('docker-' + name)).resolve())
                self.assertEqual(original, (developer / 'config.json').read_text())
            self.assertEqual(2, discover.call_count)
            self.assertTrue(all(call.kwargs['timeout'] == 10 for call in discover.call_args_list))
        self.assertFalse(temporary.exists())
        self.assertEqual(before, env)
        self.assertEqual(original, (developer / 'config.json').read_text())

    def test_public_registry_temporary_config_is_removed_after_failure(self):
        metadata = self.installed_docker_metadata()
        with patch.object(CI.subprocess, 'check_output', return_value=metadata):
            with self.assertRaisesRegex(RuntimeError, 'fixture stage failed'):
                with CI.public_registry_environment({'DOCKER_HOST': 'unix:///fixture/docker.sock'}) as env:
                    temporary = Path(env['DOCKER_CONFIG'])
                    raise RuntimeError('fixture stage failed')
        self.assertFalse(temporary.exists())

    def test_public_registry_mode_does_not_replace_remote_or_tls_endpoints(self):
        for env in [{'DOCKER_HOST': 'tcp://remote:2376'},
                    {'DOCKER_HOST': 'ssh://remote'},
                    {'DOCKER_HOST': 'unix:///fixture/docker.sock', 'DOCKER_TLS_VERIFY': '1'}]:
            with self.subTest(env=env), patch.object(CI.subprocess, 'check_output') as discover:
                with self.assertRaisesRegex(ValueError, 'local Unix'):
                    with CI.public_registry_environment(env):
                        self.fail('Unsupported endpoint must not be used')
                discover.assert_not_called()

    def test_public_registry_mode_requires_existing_plugins_without_installing(self):
        with patch.object(CI.subprocess, 'check_output', return_value='{"plugins": []}'):
            with self.assertRaisesRegex(ValueError, 'Buildx and Compose'):
                with CI.public_registry_environment({'DOCKER_HOST': 'unix:///fixture/docker.sock'}):
                    self.fail('Missing plugins must fail')

    def test_desktop_socket_mount_is_separate_from_host_socket_and_preserves_override(self):
        metadata = self.installed_docker_metadata()
        for override in [None, '/var/run/existing.sock']:
            with self.subTest(override=override), patch.object(CI.sys, 'platform', 'darwin'), \
                    patch.object(CI.subprocess, 'check_output', return_value=metadata):
                original = {'DOCKER_HOST': 'unix:///fixture/host/docker.sock'}
                if override:
                    original['TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE'] = override
                with CI.public_registry_environment(original) as env:
                    self.assertEqual('unix:///fixture/host/docker.sock', env['DOCKER_HOST'])
                    self.assertEqual(override or '/var/run/docker.sock', env['TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE'])
                    self.assertNotIn('TESTCONTAINERS_RYUK_DISABLED', env)

    def test_public_registry_environment_is_passed_to_all_stage_commands(self):
        metadata = self.installed_docker_metadata()
        with patch.object(CI.subprocess, 'check_output', return_value=metadata), \
                patch.object(CI, 'perform_isolated') as perform:
            CI.perform('images', self.report, {'DOCKER_HOST': 'unix:///fixture/docker.sock'})
        isolated = perform.call_args.args[2]
        self.assertEqual('unix:///fixture/docker.sock', isolated['DOCKER_HOST'])
        self.assertFalse(Path(isolated['DOCKER_CONFIG']).exists())

    def test_cleanup_uses_isolated_environment_and_records_command_failure(self):
        metadata = self.installed_docker_metadata()
        with patch.object(CI, 'environment', return_value={'DOCKER_HOST': 'unix:///fixture/docker.sock'}), \
                patch.object(CI.subprocess, 'check_output', return_value=metadata), \
                patch.object(CI, 'execute', side_effect=[subprocess.CalledProcessError(1, ['fixture']), None]) as execute, \
                patch.object(CI, 'save') as save:
            with self.assertRaisesRegex(ValueError, 'cleanup did not complete'):
                CI.cleanup(self.report)
        self.assertEqual('failed', self.report['cleanup'])
        self.assertEqual(2, execute.call_count)
        directories = {call.args[1]['DOCKER_CONFIG'] for call in execute.call_args_list}
        self.assertEqual(1, len(directories))
        self.assertTrue(all(not Path(directory).exists() for directory in directories))
        save.assert_called_once_with(self.report)

    def test_wrong_image_revision_or_build_is_rejected(self):
        labels = {'org.opencontainers.image.revision': self.sha, 'io.infrastructure.build-id': '12'}
        image = {'Id': 'sha256:fixture', 'Config': {'Labels': labels}}
        with patch.object(CI, 'execute', side_effect=lambda *args, **kwargs: json.dumps([image])):
            self.assertEqual('sha256:fixture', CI.inspect_image('fixture', self.report)['id'])
            labels['io.infrastructure.build-id'] = '11'
            with self.assertRaises(ValueError):
                CI.inspect_image('fixture', self.report)

    def test_container_must_use_exact_built_image_id(self):
        self.report['images'] = {key: {'id': 'sha256:expected'} for key in CI.IMAGE_KEYS}
        with patch.object(CI, 'execute', side_effect=['container-id', '[{"Image":"sha256:other"}]']):
            with self.assertRaisesRegex(ValueError, 'not the image'):
                CI.attest(self.report)
        with patch.object(CI, 'execute', side_effect=['container-id', '[{"Image":"sha256:expected"}]'] * 3):
            CI.attest(self.report, auth=True)
        self.assertTrue(self.report['authRuntimeAttested'])

    def test_out_of_order_stage_cannot_produce_success(self):
        with patch.object(CI, 'load', return_value=self.report), patch.object(CI, 'identity', return_value=(self.sha, self.tree)), \
                patch.object(sys, 'argv', ['ci_pipeline.py', 'verify']), patch.object(CI, 'perform') as perform:
            with self.assertRaisesRegex(ValueError, 'preceding'):
                CI.main()
            perform.assert_not_called()

    def test_failed_stage_retains_failure_and_cannot_be_complete(self):
        with patch.object(CI, 'load', return_value=self.report), patch.object(CI, 'identity', return_value=(self.sha, self.tree)), \
                patch.object(sys, 'argv', ['ci_pipeline.py', 'quality']), patch.object(CI, 'save'), \
                patch.object(CI, 'perform', side_effect=ValueError('fixture failure')):
            with self.assertRaises(ValueError):
                CI.main()
        self.assertEqual('failed', self.report['stages']['quality']['result'])
        self.assertFalse(self.report['complete'])

    def test_source_change_during_stage_is_rejected(self):
        with patch.object(CI, 'load', return_value=self.report), \
                patch.object(CI, 'identity', side_effect=[(self.sha, self.tree), ('0' * 40, self.tree)]), \
                patch.object(sys, 'argv', ['ci_pipeline.py', 'quality']), patch.object(CI, 'save'), patch.object(CI, 'perform'):
            with self.assertRaisesRegex(ValueError, 'Source changed'):
                CI.main()
        self.assertEqual('failed', self.report['stages']['quality']['result'])

    def test_recovery_retains_image_overlay_in_pinned_mode(self):
        with patch.dict(os.environ, {'CI_IMMUTABLE_IMAGES': '1', 'CI_PROJECT_NAME': CI.PROJECT}):
            spec = importlib.util.spec_from_file_location('drill_fixture', ROOT / 'scripts/test-alerting.py')
            drill = importlib.util.module_from_spec(spec)
            spec.loader.exec_module(drill)
        self.assertIn('compose.ci-images.yaml', drill.COMMAND)


if __name__ == '__main__':
    unittest.main()
