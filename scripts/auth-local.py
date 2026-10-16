#!/usr/bin/env python3
"""Provision an isolated local three-account realm; never print credentials."""
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


def write_private(path, value):
    descriptor = os.open(path, os.O_WRONLY | os.O_CREAT | os.O_TRUNC, 0o600)
    with os.fdopen(descriptor, 'w') as output:
        output.write(value)
    path.chmod(0o600)


def realm(credentials, web_origin='http://127.0.0.1:5176'):
    users = []
    for key, username, first, last in [('admin', 'admin', 1, 20), ('a', 'user-a', 1, 10), ('b', 'user-b', 11, 20)]:
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
    return {'realm': REALM, 'enabled': True, 'registrationAllowed': False, 'resetPasswordAllowed': False,
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
    parser.add_argument('--ci', action='store_true', help='Use fixed isolated CI ports, credentials and Compose project')
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
    LOCAL.mkdir(mode=0o700, parents=True, exist_ok=True)
    LOCAL.chmod(0o700)
    credentials_path = LOCAL / 'credentials.json'
    if credentials_path.exists() and not args.ci:
        credentials = json.loads(credentials_path.read_text())
    else:
        credentials = {'users': {key: {'username': name, 'password': secrets.token_urlsafe(24)}
                                for key, name in [('admin', 'admin'), ('a', 'user-a'), ('b', 'user-b')]},
                       'bootstrap': secrets.token_urlsafe(32), 'appDatabase': secrets.token_urlsafe(32),
                       'identityDatabase': secrets.token_urlsafe(32)}
        write_private(credentials_path, json.dumps(credentials, indent=2) + '\n')
    write_private(LOCAL / 'realm.json', json.dumps(realm(credentials, web_origin), indent=2) + '\n')
    write_private(LOCAL / 'compose.env', '\n'.join([
        'AUTH_BOOTSTRAP_PASSWORD=' + credentials['bootstrap'],
        'AUTH_APP_DB_PASSWORD=' + credentials['appDatabase'],
        'AUTH_IDENTITY_DB_PASSWORD=' + credentials['identityDatabase'],
        *(['AUTH_KEYCLOAK_PORT=8193', 'AUTH_WEB_PORT=5186', 'AUTH_API_PORT=8093',
           'AUTH_PROMETHEUS_PORT=9096', 'AUTH_ALERTMANAGER_PORT=9196',
           'AUTH_REALM_FILE=' + str(LOCAL / 'realm.json')] if args.ci else []), '']))
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
