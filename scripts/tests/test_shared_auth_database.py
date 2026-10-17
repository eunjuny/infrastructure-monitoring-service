"""Offline contracts for single-server auth storage and private configuration."""
import importlib.util
import json
from pathlib import Path
from tempfile import TemporaryDirectory
import unittest
from unittest.mock import patch

ROOT = Path(__file__).resolve().parents[2]


def load(name, path):
    spec = importlib.util.spec_from_file_location(name, ROOT / path)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


AUTH = load('shared_auth_config', 'scripts/auth-local.py')
MIGRATION = load('shared_auth_migration', 'scripts/migrate-auth-database.py')


class SharedAuthenticationDatabaseTest(unittest.TestCase):
    def test_single_server_and_database_initialization_dependency(self):
        compose = (ROOT / 'compose.auth.yaml').read_text()
        self.assertNotIn('  keycloak-db:', compose)
        self.assertNotIn('identity-data:', compose)
        self.assertIn('jdbc:postgresql://db:5432/identity', compose)
        self.assertIn('POSTGRES_USER: infrastructure_admin', compose)
        self.assertEqual(2, compose.count('db-init: {condition: service_completed_successfully}'))
        self.assertNotIn('keycloak-db:', (ROOT / 'compose.auth-ci.yaml').read_text())

    def test_initializer_separates_credentials_and_removes_cross_access(self):
        script = (ROOT / 'infra/postgres/init-auth-databases.sh').read_text()
        for role in ['monitoring', 'identity']:
            self.assertIn('ALTER ROLE ' + role + ' NOSUPERUSER NOCREATEDB NOCREATEROLE', script)
            self.assertIn('REVOKE ALL ON DATABASE ' + role + ' FROM PUBLIC', script)
        self.assertIn('REVOKE ALL ON DATABASE monitoring FROM identity', script)
        self.assertIn('REVOKE ALL ON DATABASE identity FROM monitoring', script)
        self.assertIn('\\getenv app_password AUTH_APP_DB_PASSWORD', script)
        self.assertNotIn('set -x', script)

    def test_adding_admin_secret_does_not_rotate_existing_credentials(self):
        credentials = {'appDatabase': 'fixture-app', 'identityDatabase': 'fixture-identity',
                       'bootstrap': 'fixture-bootstrap', 'users': {}}
        previous = json.loads(json.dumps(credentials))
        value = AUTH.database_admin(credentials)
        self.assertEqual(value, AUTH.database_admin(credentials))
        self.assertGreaterEqual(len(value), 32)
        self.assertEqual(previous, {k: v for k, v in credentials.items() if k != 'databaseAdmin'})

    def test_shared_environment_is_private_and_keeps_ci_ports_isolated(self):
        credentials = {'appDatabase': 'fixture-app', 'identityDatabase': 'fixture-identity',
                       'bootstrap': 'fixture-bootstrap'}
        with TemporaryDirectory() as temporary:
            local = Path(temporary)
            AUTH.write_environment(local, credentials, ci=True)
            self.assertEqual(0o600, (local / 'compose.env').stat().st_mode & 0o777)
            environment = (local / 'compose.env').read_text()
            self.assertIn('AUTH_DB_ADMIN_PASSWORD=', environment)
            self.assertIn('AUTH_WEB_PORT=5186', environment)
            self.assertNotIn('AUTH_WEB_PORT=5176', environment)

    def test_secret_literal_escaping(self):
        self.assertEqual("'a''b'", MIGRATION.literal("a'b"))

    def test_unrelated_container_labels_are_rejected(self):
        with patch.object(MIGRATION, 'run', return_value=b'{"com.docker.compose.project":"other"}'):
            with self.assertRaisesRegex(RuntimeError, 'Unexpected Compose target'):
                MIGRATION.require_container('ignored', 'db')

    def test_existing_target_database_is_never_overwritten(self):
        with patch.object(MIGRATION, 'require_container'), patch.object(MIGRATION, 'sql', return_value='1'), \
                patch.object(MIGRATION, 'backup') as backup, patch.object(MIGRATION, 'run') as run:
            with self.assertRaisesRegex(RuntimeError, 'refusing overwrite'):
                MIGRATION.apply()
            backup.assert_not_called()
            run.assert_not_called()

    def test_snapshot_rejects_unexpected_table_names(self):
        with patch.object(MIGRATION, 'sql', return_value='unexpected;sql'):
            with self.assertRaisesRegex(RuntimeError, 'Unexpected database tables'):
                MIGRATION.snapshot('ignored', 'ignored', 'ignored')

    def test_snapshot_order_is_independent_of_parallel_append(self):
        with patch.object(MIGRATION, 'sql', side_effect=['a\nb', 'b|1|digest-b\na|1|digest-a']):
            value = MIGRATION.snapshot('ignored', 'ignored', 'ignored')
        self.assertEqual('a|1|digest-a\nb|1|digest-b', value)

    def test_resume_refuses_missing_snapshots_before_any_write(self):
        with TemporaryDirectory() as temporary:
            local = Path(temporary)
            (local / 'database-migration.json').write_text('{"phase":"databases-initialized"}')
            with patch.object(MIGRATION, 'LOCAL', local), patch.object(MIGRATION, 'require_container'), \
                    patch.object(MIGRATION, 'run') as run:
                with self.assertRaisesRegex(RuntimeError, 'No complete pre-migration snapshots'):
                    MIGRATION.resume()
                run.assert_not_called()


if __name__ == '__main__':
    unittest.main()
