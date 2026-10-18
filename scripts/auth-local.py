#!/usr/bin/env python3
"""Provision evaluation accounts or isolated CI accounts; never print secrets."""
import json
import argparse
import http.client
import os
from pathlib import Path
import secrets
import subprocess
import time
import urllib.error
import urllib.request

ROOT = Path(__file__).resolve().parents[1]
LOCAL = ROOT / '.local/auth'
REALM = 'infrastructure-monitoring'
ISSUER = 'http://127.0.0.1:8183/realms/' + REALM


def initial_credentials(ci=False):
    # Public evaluation fixtures only; infrastructure and CI credentials stay random.
    return {
        'users': {
            key: {'username': username, 'password': secrets.token_urlsafe(24) if ci else '1234'}
            for key, username in [('admin', 'admin'), ('a', 'user1'), ('b', 'user2')]
        },
        'bootstrap': secrets.token_urlsafe(32),
        'appDatabase': secrets.token_urlsafe(32),
        'identityDatabase': secrets.token_urlsafe(32),
    }


def validate_account_names(credentials):
    expected = {'admin': 'admin', 'a': 'user1', 'b': 'user2'}
    if any(credentials.get('users', {}).get(key, {}).get('username') != username
           for key, username in expected.items()):
        raise ValueError('Legacy local account names detected; migrate existing users and private mappings '
                         'as documented in docs/USER_SCOPES_AND_LOGIN_THEME.md; do not delete credentials or volumes')


def write_private(path, value):
    descriptor = os.open(path, os.O_WRONLY | os.O_CREAT | os.O_TRUNC, 0o600)
    with os.fdopen(descriptor, 'w') as output:
        output.write(value)
    path.chmod(0o600)


def database_admin(credentials):
    """Upgrade the private secret bundle without rotating any existing credential."""
    if 'databaseAdmin' not in credentials:
        credentials['databaseAdmin'] = secrets.token_urlsafe(32)
    return credentials['databaseAdmin']


def write_environment(local, credentials, ci=False):
    write_private(local / 'compose.env', '\n'.join([
        'AUTH_BOOTSTRAP_PASSWORD=' + credentials['bootstrap'],
        'AUTH_DB_ADMIN_PASSWORD=' + database_admin(credentials),
        'AUTH_APP_DB_PASSWORD=' + credentials['appDatabase'],
        'AUTH_IDENTITY_DB_PASSWORD=' + credentials['identityDatabase'],
        'ALERT_EMAIL_CONTROL_TOKEN=' + credentials.setdefault('alertEmailControl', secrets.token_urlsafe(32)),
        # Metadata only, not a hash/value of the secret. Compose must restart both readers on changes.
        'AUTH_SMTP_REVISION=' + (str((local / 'smtp.json').stat().st_mtime_ns) if (local / 'smtp.json').exists() else 'initial'),
        *(['AUTH_KEYCLOAK_PORT=8193', 'AUTH_WEB_PORT=5186', 'AUTH_API_PORT=8093',
           'AUTH_PROMETHEUS_PORT=9096', 'AUTH_ALERTMANAGER_PORT=9196',
           'AUTH_REALM_FILE=' + str(local / 'realm.json'), 'AUTH_SMTP_FILE=' + str(local / 'smtp.json')] if ci else []), '']))


def realm(credentials, web_origin='http://127.0.0.1:5176'):
    users = []
    for key, username, first, last in [('admin', 'admin', 1, 20), ('a', 'user1', 1, 10), ('b', 'user2', 11, 20)]:
        users.append({'username': username, 'enabled': True, 'firstName': 'Sample', 'lastName': username,
                      'email': username + '@example.invalid', 'requiredActions': [],
                      'credentials': [{'type': 'password', 'value': credentials['users'][key]['password'], 'temporary': False}],
                      'clientRoles': {'monitoring-api': ['ADMIN' if key == 'admin' else 'VIEWER']},
                      'groups': [] if key == 'admin' else ['/cluster-' + str(number) for number in range(first, last + 1)]})
    mappers = [
        {'name': 'api-audience', 'protocol': 'openid-connect', 'protocolMapper': 'oidc-audience-mapper',
         'config': {'included.client.audience': 'monitoring-api', 'access.token.claim': 'true', 'id.token.claim': 'false'}},
        {'name': 'api-roles', 'protocol': 'openid-connect', 'protocolMapper': 'oidc-usermodel-client-role-mapper',
         'config': {'usermodel.clientRoleMapping.clientId': 'monitoring-api', 'claim.name': 'resource_access.monitoring-api.roles',
                    'multivalued': 'true', 'jsonType.label': 'String', 'access.token.claim': 'true', 'id.token.claim': 'false'}},
        {'name': 'cluster-groups', 'protocol': 'openid-connect', 'protocolMapper': 'oidc-group-membership-mapper',
         'config': {'claim.name': 'groups', 'full.path': 'false', 'access.token.claim': 'true', 'id.token.claim': 'false'}}]
    return {'realm': REALM, 'enabled': True, 'displayName': 'Infra Monitor', 'displayNameHtml': 'Infra Monitor',
            'loginTheme': 'infra-monitor', 'internationalizationEnabled': True,
            'supportedLocales': ['ko', 'en'], 'defaultLocale': 'ko',
            'registrationAllowed': False, 'resetPasswordAllowed': False,
            'loginWithEmailAllowed': False, 'verifyEmail': False, 'accessTokenLifespan': 120,
            'sslRequired': 'external', 'bruteForceProtected': True,
            'roles': {'client': {'monitoring-api': [{'name': 'ADMIN'}, {'name': 'VIEWER'}]}},
            'groups': [{'name': 'cluster-' + str(number)} for number in range(1, 21)],
            'clients': [{'clientId': 'monitoring-api', 'bearerOnly': True, 'enabled': True},
                        {'clientId': 'monitoring-web', 'enabled': True, 'publicClient': True,
                         'standardFlowEnabled': True, 'implicitFlowEnabled': False, 'directAccessGrantsEnabled': False,
                         'redirectUris': [web_origin + '/*'], 'webOrigins': [web_origin],
                         'attributes': {'pkce.code.challenge.method': 'S256',
                                        'post.logout.redirect.uris': web_origin + '/*'},
                         'protocolMappers': mappers}], 'users': users}


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--ci', action='store_true', help='Use isolated CI ports and temporary credentials')
    parser.add_argument('--stop', action='store_true', help='Remove only CI containers/networks, preserving volumes/secrets')
    args = parser.parse_args()
    if args.stop and not args.ci:
        parser.error('--stop is restricted to --ci')
    global LOCAL
    LOCAL = ROOT / ('.local/auth-ci' if args.ci else '.local/auth')
    web_origin = 'http://127.0.0.1:5186' if args.ci else 'http://127.0.0.1:5176'
    issuer = ('http://127.0.0.1:8193' if args.ci else 'http://127.0.0.1:8183') + '/realms/' + REALM
    command = ['docker', 'compose', '--env-file', str(LOCAL / 'compose.env'), '-f', 'compose.auth.yaml']
    if args.ci:
        command += ['-f', 'compose.auth-ci.yaml', '-p', 'infrastructure-monitoring-ci-auth']
    if args.stop:
        if (LOCAL / 'compose.env').exists():
            subprocess.run(command + ['down', '--remove-orphans'], cwd=ROOT, check=True)
        return
    if not args.ci:
        legacy = subprocess.run(['docker', 'container', 'inspect',
                                 'infrastructure-monitoring-auth-keycloak-db-1'],
                                capture_output=True, timeout=10)
        if legacy.returncode == 0:
            raise SystemExit('Legacy identity database detected. Run python3 scripts/migrate-auth-database.py --apply '
                             'as described in docs/SHARED_AUTH_DATABASE.md. Do not delete volumes.')
    LOCAL.mkdir(mode=0o700, parents=True, exist_ok=True)
    LOCAL.chmod(0o700)
    credentials_path = LOCAL / 'credentials.json'
    if credentials_path.exists() and not args.ci:
        credentials = json.loads(credentials_path.read_text())
    else:
        credentials = initial_credentials(ci=args.ci)
        write_private(credentials_path, json.dumps(credentials, indent=2) + '\n')
    try:
        validate_account_names(credentials)
    except ValueError as cause:
        raise SystemExit(str(cause)) from None
    database_admin(credentials)
    credentials.setdefault('alertEmailControl', secrets.token_urlsafe(32))
    write_private(credentials_path, json.dumps(credentials, indent=2) + '\n')
    smtp_file = LOCAL / 'smtp.json'
    if not smtp_file.exists():
        write_private(smtp_file, '{}\n')
    write_private(LOCAL / 'realm.json', json.dumps(realm(credentials, web_origin), indent=2) + '\n')
    write_environment(LOCAL, credentials, args.ci)
    launch = ['up', '-d', '--no-build', '--pull', 'never'] if args.ci and os.environ.get('CI_IMMUTABLE_IMAGES') == '1' else ['up', '-d', '--build']
    subprocess.run(command + launch, cwd=ROOT, check=True)
    for attempt in range(120):
        try:
            with urllib.request.urlopen(issuer + '/.well-known/openid-configuration', timeout=3) as response:
                assert json.load(response)['issuer'] == issuer
            with urllib.request.urlopen(web_origin + '/api/auth/config', timeout=3) as response:
                config = json.load(response)
                assert config['enabled'] is True and config['issuer'] == issuer
            print('Isolated CI authentication ready' if args.ci else 'Authentication environment ready: http://127.0.0.1:5176/')
            print('Private account files preserved (0600; values suppressed)')
            return
        except (urllib.error.URLError, TimeoutError, AssertionError, http.client.RemoteDisconnected, ConnectionError):
            time.sleep(2)
    raise SystemExit('Authentication readiness failed; inspect local service status without printing configuration/secrets')


if __name__ == '__main__':
    main()
