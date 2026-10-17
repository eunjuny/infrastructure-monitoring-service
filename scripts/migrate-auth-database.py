#!/usr/bin/env python3
"""Explicit local-only, backed-up migration. Never print SQL, credentials or dumps."""
import argparse
import datetime
import fcntl
import importlib.util
import json
import os
from pathlib import Path
import re
import subprocess
import sys
import time
import urllib.request

ROOT = Path(__file__).resolve().parents[1]
LOCAL = ROOT / '.local/auth'
DB = 'infrastructure-monitoring-auth-db-1'
LEGACY = 'infrastructure-monitoring-auth-keycloak-db-1'
spec = importlib.util.spec_from_file_location('auth_local', ROOT / 'scripts/auth-local.py')
AUTH = importlib.util.module_from_spec(spec)
spec.loader.exec_module(AUTH)


def run(command, data=None, timeout=120):
    result = subprocess.run(command, input=data, capture_output=True, timeout=timeout, cwd=ROOT)
    if result.returncode:
        raise RuntimeError('Local database operation failed; sensitive diagnostics suppressed')
    return result.stdout


def compose(*args):
    return ['docker', 'compose', '--env-file', str(LOCAL / 'compose.env'), '-f', 'compose.auth.yaml', *args]


def sql(container, database, user, statement):
    return run(['docker', 'exec', '-i', container, 'psql', '-X', '-q', '-At',
                '-v', 'ON_ERROR_STOP=1', '-U', user, '-d', database], statement.encode()).decode().strip()


def literal(value):
    return "'" + value.replace("'", "''") + "'"


def require_container(container, service):
    labels = json.loads(run(['docker', 'inspect', '--format', '{{json .Config.Labels}}', container]))
    if labels.get('com.docker.compose.project') != 'infrastructure-monitoring-auth' or labels.get('com.docker.compose.service') != service:
        raise RuntimeError('Unexpected Compose target; refusing migration')


def snapshot(container, database, user):
    tables = sql(container, database, user,
                 "SELECT tablename FROM pg_tables WHERE schemaname='public' ORDER BY tablename").splitlines()
    if not tables or any(not re.fullmatch(r'[a-z_][a-z_0-9]*', table) for table in tables):
        raise RuntimeError('Unexpected database tables; refusing migration')
    statements = ["SELECT " + literal(table) + ", count(*), md5(coalesce(string_agg(row_to_json(r)::text,'' ORDER BY row_to_json(r)::text),'')) FROM public.\""
                  + table + '\" r' for table in tables]
    # PostgreSQL may execute UNION ALL branches using Parallel Append in any order.
    return '\n'.join(sorted(sql(container, database, user, ' UNION ALL '.join(statements)).splitlines()))


def backup(container, database, user, destination):
    descriptor = os.open(destination, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
    with os.fdopen(descriptor, 'wb') as output:
        result = subprocess.run(['docker', 'exec', container, 'pg_dump', '-Fc', '-U', user, '-d', database],
                                stdout=output, stderr=subprocess.PIPE, timeout=120)
    if result.returncode or destination.stat().st_size == 0:
        raise RuntimeError('Database backup failed; no cutover performed')


def verify():
    require_container(DB, 'db')
    roles = sql(DB, 'postgres', 'infrastructure_admin',
                "SELECT rolname,rolsuper,rolcreatedb,rolcreaterole,rolreplication,rolbypassrls FROM pg_roles "
                "WHERE rolname IN ('monitoring','identity') ORDER BY rolname")
    if roles != 'identity|f|f|f|f|f\nmonitoring|f|f|f|f|f':
        raise RuntimeError('Application roles are not isolated from administration')
    for database, role in [('monitoring', 'monitoring'), ('identity', 'identity')]:
        if sql(DB, database, role, 'SELECT 1') != '1':
            raise RuntimeError('Own database access failed')
        denied = subprocess.run(['docker', 'exec', DB, 'psql', '-X', '-q', '-At', '-U', role,
                                 '-d', 'identity' if database == 'monitoring' else 'monitoring', '-c', 'SELECT 1'],
                                capture_output=True, timeout=15)
        if denied.returncode == 0:
            raise RuntimeError('Cross-database access was not denied')
    with urllib.request.urlopen('http://127.0.0.1:8183/realms/infrastructure-monitoring/.well-known/openid-configuration', timeout=5) as response:
        if json.load(response)['issuer'] != AUTH.ISSUER:
            raise RuntimeError('Unexpected identity issuer')
    with urllib.request.urlopen('http://127.0.0.1:5176/api/auth/config', timeout=5) as response:
        if json.load(response)['enabled'] is not True:
            raise RuntimeError('Authentication is not enabled')
    print('Shared PostgreSQL verified: separate non-admin roles; own access allowed; cross access denied')


def apply():
    require_container(DB, 'db')
    require_container(LEGACY, 'keycloak-db')
    if sql(DB, 'postgres', 'monitoring', "SELECT count(*) FROM pg_database WHERE datname='identity'") != '0':
        raise RuntimeError('Target identity database already exists; refusing overwrite. Review the previous backup/state first')
    mounts = json.loads(run(['docker', 'inspect', '--format', '{{json .Mounts}}', DB]))
    volumes = [mount['Name'] for mount in mounts if mount.get('Type') == 'volume' and mount.get('Destination') == '/var/lib/postgresql/data']
    if len(volumes) != 1 or not re.fullmatch(r'infrastructure-monitoring-auth_[a-z-]+', volumes[0]):
        raise RuntimeError('Unexpected original database volume')
    existing = subprocess.run(['docker', 'volume', 'inspect', 'infrastructure-monitoring-auth_auth-shared-data'],
                              capture_output=True, timeout=10)
    if existing.returncode == 0:
        raise RuntimeError('Shared target volume already exists; refusing overwrite')
    credentials = json.loads((LOCAL / 'credentials.json').read_text())
    AUTH.validate_account_names(credentials)
    AUTH.database_admin(credentials)
    stamp = datetime.datetime.now(datetime.timezone.utc).strftime('%Y%m%dT%H%M%S%fZ')
    directory = LOCAL / 'database-backups' / stamp
    directory.mkdir(mode=0o700, parents=True)
    directory.parent.chmod(0o700)
    state = {'phase': 'prepared', 'backup': str(directory.relative_to(ROOT)), 'oldDatabaseVolumePreserved': True,
             'originalApplicationVolume': volumes[0]}
    def checkpoint(phase):
        state['phase'] = phase
        AUTH.write_private(LOCAL / 'database-migration.json', json.dumps(state, indent=2) + '\n')
    checkpoint('prepared')
    AUTH.write_private(LOCAL / 'credentials.json', json.dumps(credentials, indent=2) + '\n')
    AUTH.write_environment(LOCAL, credentials)
    try:
        run(compose('stop', 'backend', 'keycloak'))
        app_before = snapshot(DB, 'monitoring', 'monitoring')
        identity_before = snapshot(LEGACY, 'identity', 'identity')
        state.update(applicationSnapshot=app_before, identitySnapshot=identity_before)
        backup(DB, 'monitoring', 'monitoring', directory / 'monitoring.dump')
        backup(LEGACY, 'identity', 'identity', directory / 'identity.dump')
        checkpoint('backed-up')
        run(compose('up', '-d', '--no-deps', '--no-build', '--pull', 'never', 'db'))
        for _ in range(30):
            try:
                if sql(DB, 'postgres', 'infrastructure_admin', 'SELECT 1') == '1':
                    break
            except RuntimeError:
                time.sleep(1)
        else:
            raise RuntimeError('Shared PostgreSQL readiness failed')
        run(compose('run', '--rm', '--no-deps', 'db-init'))
        checkpoint('databases-initialized')
        for database, role in [('monitoring', 'monitoring'), ('identity', 'identity')]:
            with (directory / (database + '.dump')).open('rb') as source:
                restored = subprocess.run(['docker', 'exec', '-i', DB, 'pg_restore', '-U', 'infrastructure_admin',
                                           '-d', database, '--role=' + role, '--no-owner', '--no-privileges',
                                           '--single-transaction', '--exit-on-error'], stdin=source,
                                          stdout=subprocess.PIPE, stderr=subprocess.PIPE, timeout=120)
            if restored.returncode:
                raise RuntimeError('Database restore failed; original volumes and private backups preserved')
        if app_before != snapshot(DB, 'monitoring', 'infrastructure_admin') or identity_before != snapshot(DB, 'identity', 'identity'):
            raise RuntimeError('Database content differs after restore; refusing cutover')
        state.update(applicationDataIdentical=True, identityDataIdentical=True)
        checkpoint('content-verified')
        run(compose('up', '-d', '--no-deps', '--no-build', '--pull', 'never', 'keycloak', 'backend'))
        run(['docker', 'exec', 'infrastructure-monitoring-auth-frontend-1', 'nginx', '-s', 'reload'])
        for _ in range(90):
            try:
                verify()
                break
            except (RuntimeError, OSError):
                time.sleep(2)
        else:
            raise RuntimeError('Post-cutover readiness failed')
        run(['docker', 'stop', LEGACY])
        checkpoint('cutover-verified')
        print('Migration completed; data matched before service restart. Run real login tests before retiring the stopped legacy container')
    except Exception:
        # No database is dropped automatically. Keep both dumps and the old identity volume.
        state['failed'] = True
        AUTH.write_private(LOCAL / 'database-migration.json', json.dumps(state, indent=2) + '\n')
        print('Migration stopped safely; original identity volume and available backups retained. See docs/SHARED_AUTH_DATABASE.md', file=sys.stderr)
        raise


def resume():
    """Resume only an already-restored database, without replacing/restoring any data."""
    require_container(DB, 'db')
    require_container(LEGACY, 'keycloak-db')
    state = json.loads((LOCAL / 'database-migration.json').read_text())
    if state.get('phase') not in {'databases-initialized', 'content-verified'} or not state.get('applicationSnapshot') or not state.get('identitySnapshot'):
        raise RuntimeError('No complete pre-migration snapshots for resume; inspect/rollback instead')
    before_app = '\n'.join(sorted(state['applicationSnapshot'].splitlines()))
    before_identity = '\n'.join(sorted(state['identitySnapshot'].splitlines()))
    if before_app != snapshot(DB, 'monitoring', 'infrastructure_admin') or before_identity != snapshot(DB, 'identity', 'identity'):
        raise RuntimeError('Content comparison failed; resume refused without modifying data')
    run(compose('up', '-d', '--no-deps', '--no-build', '--pull', 'never', 'keycloak', 'backend'))
    run(['docker', 'exec', 'infrastructure-monitoring-auth-frontend-1', 'nginx', '-s', 'reload'])
    for _ in range(90):
        try:
            verify()
            break
        except (RuntimeError, OSError):
            time.sleep(2)
    else:
        raise RuntimeError('Resumed cutover readiness failed')
    run(['docker', 'stop', LEGACY])
    state.update(phase='cutover-verified', applicationDataIdentical=True, identityDataIdentical=True, failed=False)
    AUTH.write_private(LOCAL / 'database-migration.json', json.dumps(state, indent=2) + '\n')
    print('Restored content verified and cutover resumed without overwriting data')


def rollback():
    """Reconnect to the preserved original identity server without dropping either DB."""
    require_container(DB, 'db')
    require_container(LEGACY, 'keycloak-db')
    state = json.loads((LOCAL / 'database-migration.json').read_text())
    volume = state.get('originalApplicationVolume', '')
    if not re.fullmatch(r'infrastructure-monitoring-auth_[a-z-]+', volume):
        raise RuntimeError('Unexpected rollback volume')
    override = LOCAL / 'database-rollback.yaml'
    AUTH.write_private(override, 'services:\n  db:\n    environment:\n'
                       '      POSTGRES_USER: monitoring\n      POSTGRES_PASSWORD: ${AUTH_APP_DB_PASSWORD}\n'
                       '    volumes: !override\n      - original-app:/var/lib/postgresql/data\n'
                       '  keycloak:\n    environment:\n'
                       '      KC_DB_URL: jdbc:postgresql://keycloak-db:5432/identity\n'
                       'volumes:\n  original-app:\n    external: true\n    name: ' + volume + '\n')
    try:
        run(compose('stop', 'keycloak', 'backend'))
        run(['docker', 'start', LEGACY])
        run(compose('-f', str(override), 'up', '-d', '--no-deps', '--no-build', '--pull', 'never', 'db'))
        for _ in range(30):
            try:
                if sql(DB, 'monitoring', 'monitoring', 'SELECT 1') == '1':
                    break
            except RuntimeError:
                time.sleep(1)
        else:
            raise RuntimeError('Original database readiness failed')
        run(compose('-f', str(override), 'up', '-d', '--no-deps', '--no-build', '--pull', 'never', 'keycloak', 'backend'))
        print('Original identity database reconnected; both databases/backups preserved. Review before another migration')
    finally:
        override.unlink(missing_ok=True)


def main():
    parser = argparse.ArgumentParser()
    mode = parser.add_mutually_exclusive_group(required=True)
    mode.add_argument('--apply', action='store_true')
    mode.add_argument('--verify', action='store_true')
    mode.add_argument('--retire-legacy', action='store_true')
    mode.add_argument('--rollback', action='store_true')
    mode.add_argument('--resume', action='store_true')
    args = parser.parse_args()
    if not LOCAL.is_dir():
        parser.error('Existing local authentication settings are required')
    descriptor = os.open(LOCAL / 'database-migration.lock', os.O_CREAT | os.O_RDWR, 0o600)
    with os.fdopen(descriptor, 'w') as lock:
        fcntl.flock(lock, fcntl.LOCK_EX | fcntl.LOCK_NB)
        if args.apply:
            apply()
        elif args.rollback:
            rollback()
        elif args.resume:
            resume()
        else:
            verify()
            if args.retire_legacy:
                state = json.loads((LOCAL / 'database-migration.json').read_text())
                if state.get('phase') != 'cutover-verified' or not state.get('identityDataIdentical'):
                    raise RuntimeError('No verified migration state; refusing container removal')
                require_container(LEGACY, 'keycloak-db')
                running = run(['docker', 'inspect', '--format', '{{.State.Running}}', LEGACY]).decode().strip()
                if running != 'false':
                    raise RuntimeError('Legacy database must already be stopped')
                run(['docker', 'rm', LEGACY])
                state['phase'] = 'legacy-container-retired'
                AUTH.write_private(LOCAL / 'database-migration.json', json.dumps(state, indent=2) + '\n')
                print('Stopped legacy container removed; its persistent volume and backups remain recoverable')


if __name__ == '__main__':
    try:
        main()
    except (RuntimeError, OSError, ValueError, KeyError, subprocess.SubprocessError):
        print('Database operation did not complete; private diagnostics suppressed; do not delete data volumes', file=sys.stderr)
        sys.exit(1)
