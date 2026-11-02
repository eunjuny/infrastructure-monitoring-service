"""Deployment admission must reject partial/unverified manifests before touching services."""
import importlib.util
from pathlib import Path
import sys
from tempfile import TemporaryDirectory
import unittest

SCRIPTS = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(SCRIPTS))
spec = importlib.util.spec_from_file_location('deploy_local', SCRIPTS / 'deploy-local.py')
DEPLOY = importlib.util.module_from_spec(spec)
spec.loader.exec_module(DEPLOY)
from ci_pipeline import STAGES, IMAGE_KEYS


class DeploymentAdmissionTest(unittest.TestCase):
    def manifest(self):
        return {'complete': True, 'authRuntimeAttested': True,
                'stages': {stage: {'result': 'passed'} for stage in STAGES},
                'images': {key: {} for key in IMAGE_KEYS}}

    def test_complete_manifest_is_admitted(self):
        DEPLOY.validate(self.manifest())

    def test_failed_or_missing_stage_blocks_deployment(self):
        for stage in STAGES:
            report = self.manifest()
            report['stages'][stage]['result'] = 'failed'
            with self.assertRaises(ValueError):
                DEPLOY.validate(report)
            del report['stages'][stage]
            with self.assertRaises(ValueError):
                DEPLOY.validate(report)

    def test_authentication_and_complete_flag_are_required(self):
        for field in ['complete', 'authRuntimeAttested']:
            report = self.manifest()
            report[field] = False
            with self.assertRaises(ValueError):
                DEPLOY.validate(report)

    def test_partial_images_are_rejected(self):
        report = self.manifest()
        del report['images']['authFrontend']
        with self.assertRaises(ValueError):
            DEPLOY.validate(report)

    def test_metadata_is_private_and_replaced(self):
        with TemporaryDirectory() as temporary:
            path = Path(temporary) / 'current.json'
            DEPLOY.private_json(path, {'result': 'pending'})
            DEPLOY.private_json(path, {'result': 'deployed'})
            self.assertEqual(0o600, path.stat().st_mode & 0o777)
            self.assertIn('deployed', path.read_text())
            self.assertFalse(path.with_suffix('.tmp').exists())
