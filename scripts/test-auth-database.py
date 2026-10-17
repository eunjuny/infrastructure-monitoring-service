#!/usr/bin/env python3
"""Real PostgreSQL initialization/privilege checks on an isolated disposable server."""
import os
from pathlib import Path
import secrets
import subprocess
from tempfile import TemporaryDirectory
import time

ROOT = Path(__file__).resolve().parents[1]


def main():
    name = 'infrastructure-auth-db-check-' + secrets.token_hex(6)
    def run(args, data=None, allowed_failure=False):
        result = subprocess.run(args, input=data, capture_output=True, timeout=60, cwd=ROOT)
        if result.returncode and not allowed_failure:
            raise RuntimeError('Isolated database check failed; diagnostics suppressed')
        return result
    def sql(role, database, statement, allowed_failure=False):
        return run(['docker', 'exec', '-i', name, 'psql', '-X', '-q', '-At', '-v', 'ON_ERROR_STOP=1',
                    '-U', role, '-d', database], statement.encode(), allowed_failure)
    created = False
    try:
        with TemporaryDirectory(prefix='infrastructure-auth-db-check-') as temporary:
            environment = Path(temporary) / 'database.env'
            descriptor = os.open(environment, os.O_CREAT | os.O_EXCL | os.O_WRONLY, 0o600)
            with os.fdopen(descriptor, 'w') as output:
                for key, value in {'POSTGRES_USER': 'infrastructure_admin', 'POSTGRES_DB': 'postgres',
                                   'POSTGRES_PASSWORD': secrets.token_urlsafe(32),
                                   'AUTH_APP_DB_PASSWORD': secrets.token_urlsafe(32),
                                   'AUTH_IDENTITY_DB_PASSWORD': secrets.token_urlsafe(32)}.items():
                    output.write(key + '=' + value + '\n')
            run(['docker', 'run', '-d', '--name', name, '--pull', 'never', '--label', 'infrastructure.auth-db-check=true',
                 '--env-file', str(environment), '--mount', 'type=tmpfs,destination=/var/lib/postgresql/data',
                 '-v', str(ROOT / 'infra/postgres/init-auth-databases.sh') + ':/init-databases.sh:ro',
                 'postgres:17-alpine'])
            created = True
            for _ in range(60):
                ready = run(['docker', 'exec', name, 'pg_isready', '-h', '127.0.0.1', '-U', 'infrastructure_admin', '-d', 'postgres'], allowed_failure=True)
                if ready.returncode == 0:
                    break
                running = run(['docker', 'inspect', '--format', '{{.State.Running}}', name]).stdout.strip()
                if running != b'true':
                    raise RuntimeError('Disposable database exited during initialization')
                time.sleep(1)
            else:
                raise RuntimeError('Isolated database initialization timed out')
            run(['docker', 'exec', name, 'sh', '/init-databases.sh'])
            sql('monitoring', 'monitoring', 'CREATE TABLE preservation_check(id integer PRIMARY KEY); INSERT INTO preservation_check VALUES(7)')
            sql('identity', 'identity', 'CREATE TABLE account_check(id integer PRIMARY KEY); INSERT INTO account_check VALUES(9)')
            run(['docker', 'exec', name, 'sh', '/init-databases.sh'])
            for role, table, value in [('monitoring', 'preservation_check', b'7'), ('identity', 'account_check', b'9')]:
                if sql(role, role, 'SELECT id FROM ' + table).stdout.strip() != value:
                    raise RuntimeError('Re-initialization did not preserve existing data')
                denied = sql(role, 'identity' if role == 'monitoring' else 'monitoring', 'SELECT 1', True)
                if denied.returncode == 0:
                    raise RuntimeError('Cross-database connection was not denied')
                for maintenance in ['postgres', 'template1']:
                    if sql(role, maintenance, 'SELECT 1', True).returncode == 0:
                        raise RuntimeError('Maintenance database access unexpectedly allowed')
                for statement in ['CREATE DATABASE unauthorized', 'CREATE ROLE unauthorized', 'ALTER ROLE ' + role + ' SUPERUSER']:
                    if sql(role, role, statement, True).returncode == 0:
                        raise RuntimeError('Application role unexpectedly has administrative privileges')
                password_key = 'AUTH_APP_DB_PASSWORD' if role == 'monitoring' else 'AUTH_IDENTITY_DB_PASSWORD'
                tcp = 'PGPASSWORD="$' + password_key + '" psql -X -q -At -h 127.0.0.1 -U ' + role + ' -d ' + role + ' -c "SELECT 1"'
                if run(['docker', 'exec', name, 'sh', '-c', tcp]).stdout.strip() != b'1':
                    raise RuntimeError('Own database TCP password authentication failed')
            print('Real PostgreSQL passed: fresh initialization, idempotence/data preservation, own TCP login, cross-database denial and admin privilege denial')
    finally:
        if created:
            result = subprocess.run(['docker', 'rm', '-f', name], capture_output=True, timeout=30)
            if result.returncode:
                raise RuntimeError('Disposable database cleanup failed')
            print('Disposable test server removed; existing services/volumes unchanged')


if __name__ == '__main__':
    try:
        main()
    except (RuntimeError, OSError, subprocess.SubprocessError):
        raise SystemExit('Database verification failed; sensitive diagnostics suppressed') from None
