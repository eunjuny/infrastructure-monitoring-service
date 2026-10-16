#!/usr/bin/env python3
import base64
import argparse
import http.cookiejar
import http.client
import json
import os
import re
from pathlib import Path
import secrets
import subprocess
import sys
import time
import urllib.error
import urllib.parse
import urllib.request
import xml.etree.ElementTree as ET

from ci_pipeline import public_registry_environment

ROOT = Path(__file__).resolve().parents[1]
parser = argparse.ArgumentParser()
parser.add_argument('--repository', type=Path, default=ROOT, help='Trusted local Git repository')
parser.add_argument('--revision', default='HEAD', help='Commit/ref resolved once before dispatch')
args = parser.parse_args()
repository = args.repository.resolve()
try:
    revision = subprocess.check_output(['git', '-C', str(repository), 'rev-parse', '--verify', '--end-of-options', args.revision + '^{commit}'],
                                       text=True, stderr=subprocess.DEVNULL).strip()
    if not re.fullmatch(r'[0-9a-f]{40}', revision):
        raise ValueError('Invalid commit')
    pipeline_source = subprocess.check_output(['git', '-C', str(repository), 'show', revision + ':Jenkinsfile'], text=True, stderr=subprocess.DEVNULL)
    subprocess.run(['git', '-C', str(repository), 'cat-file', '-e', revision + ':scripts/ci_pipeline.py'], check=True, stderr=subprocess.DEVNULL)
except (OSError, ValueError, subprocess.SubprocessError):
    raise SystemExit('Requested commit must contain Jenkinsfile and scripts/ci_pipeline.py. Commit changes explicitly before running; no working-tree fallback.')
LOCAL = ROOT / '.local'
URL = 'http://127.0.0.1:8182'
LOCAL.mkdir(mode=0o700, exist_ok=True)
password_path = LOCAL / 'jenkins-admin-password'
if not password_path.exists():
    descriptor = os.open(password_path, os.O_CREAT | os.O_EXCL | os.O_WRONLY, 0o600)
    with os.fdopen(descriptor, 'w') as secret_file:
        secret_file.write(secrets.token_urlsafe(32))
password = password_path.read_text().strip()
if len(password) < 24:
    raise SystemExit('Local CI secret is invalid')
with public_registry_environment(dict(os.environ)) as docker_env:
    subprocess.run(['docker', 'compose', '-f', 'compose.jenkins.yaml', 'up', '-d', '--build'],
                   cwd=ROOT, env=docker_env, check=True)
opener = urllib.request.build_opener(urllib.request.HTTPCookieProcessor(http.cookiejar.CookieJar()))
authorization = 'Basic ' + base64.b64encode(('ci-admin:' + password).encode()).decode()
crumb = {}

def request(path, data=None, content_type=None):
    headers = {'Authorization': authorization, **crumb}
    if content_type:
        headers['Content-Type'] = content_type
    with opener.open(urllib.request.Request(URL + path, data=data, headers=headers), timeout=20) as response:
        return response.read()

for attempt in range(90):
    try:
        request('/api/json')
        break
    except (urllib.error.URLError, TimeoutError, http.client.RemoteDisconnected, ConnectionError):
        time.sleep(1)
else:
    raise SystemExit('Jenkins did not become ready')
try:
    urllib.request.urlopen(URL + '/api/json', timeout=5)
    raise SystemExit('Anonymous access unexpectedly allowed')
except urllib.error.HTTPError as error:
    if error.code not in (401, 403):
        raise SystemExit('Unexpected anonymous access response')
issuer = json.loads(request('/crumbIssuer/api/json'))
crumb = {issuer['crumbRequestField']: issuer['crumb']}
agent_root = LOCAL / 'jenkins-agent'
agent_root.mkdir(mode=0o700, exist_ok=True)
script = """
import jenkins.model.Jenkins
import hudson.model.Node
import hudson.slaves.DumbSlave
import hudson.slaves.JNLPLauncher
import hudson.slaves.RetentionStrategy
def instance=Jenkins.get()
if(instance.getNode('infrastructure-local')==null) {
  instance.addNode(new DumbSlave('infrastructure-local','Local trusted assignment runner',REMOTE,'1',Node.Mode.EXCLUSIVE,'infrastructure-local',new JNLPLauncher(),new RetentionStrategy.Always(),[]))
}
print(instance.getNode('infrastructure-local').toComputer().getJnlpMac())
""".replace('REMOTE', json.dumps(str(agent_root)))
agent_secret = request('/scriptText', urllib.parse.urlencode({'script': script}).encode()).decode().strip()
if not re.fullmatch(r'[0-9a-f]{64}', agent_secret):
    raise SystemExit('Could not provision local agent')
secret_path = agent_root / 'agent-secret'
descriptor = os.open(secret_path, os.O_CREAT | os.O_TRUNC | os.O_WRONLY, 0o600)
with os.fdopen(descriptor, 'w') as secret_file:
    secret_file.write(agent_secret)
agent_jar = agent_root / 'agent.jar'
agent_jar.write_bytes(request('/jnlpJars/agent.jar'))
agent_log = agent_root / 'agent.log'
agent_state = json.loads(request('/computer/infrastructure-local/api/json'))
if agent_state.get('offline', True):
    log_descriptor = os.open(agent_log, os.O_CREAT | os.O_APPEND | os.O_WRONLY, 0o600)
    with os.fdopen(log_descriptor, 'ab') as log:
        agent = subprocess.Popen(['java', '-jar', str(agent_jar), '-url', URL, '-secret', '@' + str(secret_path), '-name',
                                  'infrastructure-local', '-webSocket', '-workDir', str(agent_root)], cwd=ROOT,
                                 stdout=log, stderr=subprocess.STDOUT, start_new_session=True)
        (agent_root / 'agent.pid').write_text(str(agent.pid))
for attempt in range(40):
    if not json.loads(request('/computer/infrastructure-local/api/json')).get('offline', True):
        break
    time.sleep(1)
else:
    raise SystemExit('Local agent did not connect; inspect the ignored agent log')
job = ET.Element('flow-definition', {'plugin': 'workflow-job'})
ET.SubElement(job, 'description').text = 'Exact committed revision checkout; trusted local Git transport, isolated build workspace'
ET.SubElement(job, 'keepDependencies').text = 'false'
properties = ET.SubElement(job, 'properties')
property_element = ET.SubElement(properties, 'hudson.model.ParametersDefinitionProperty')
definitions = ET.SubElement(property_element, 'parameterDefinitions')
for name, value in [('SOURCE_REPOSITORY', str(repository)), ('SOURCE_REVISION', revision)]:
    parameter = ET.SubElement(definitions, 'hudson.model.StringParameterDefinition')
    ET.SubElement(parameter, 'name').text = name
    ET.SubElement(parameter, 'defaultValue').text = value
definition = ET.SubElement(job, 'definition', {'class': 'org.jenkinsci.plugins.workflow.cps.CpsFlowDefinition', 'plugin': 'workflow-cps'})
ET.SubElement(definition, 'script').text = pipeline_source
ET.SubElement(definition, 'sandbox').text = 'true'
ET.SubElement(job, 'disabled').text = 'false'
job_name = 'infrastructure-monitoring'
try:
    request('/job/' + job_name + '/api/json')
    request('/job/' + job_name + '/config.xml', ET.tostring(job), 'application/xml')
except urllib.error.HTTPError as error:
    if error.code != 404:
        raise
    request('/createItem?name=' + job_name, ET.tostring(job), 'application/xml')
number = json.loads(request('/job/' + job_name + '/api/json'))['nextBuildNumber']
request('/job/' + job_name + '/buildWithParameters?' + urllib.parse.urlencode({'SOURCE_REPOSITORY': str(repository), 'SOURCE_REVISION': revision}), b'')
print('Jenkins authenticated; pinned commit', revision, 'build', number, 'started', flush=True)
for attempt in range(150):
    try:
        state = json.loads(request('/job/' + job_name + '/' + str(number) + '/api/json'))
        if not state.get('building', True):
            console = request('/job/' + job_name + '/' + str(number) + '/consoleText').decode()
            secret_values = [password, agent_secret]
            checkout = agent_root / 'workspace' / job_name / 'runs' / str(number) / 'source'
            for private in [LOCAL / 'auth/credentials.json', LOCAL / 'auth-ci/credentials.json', checkout / '.local/auth-ci/credentials.json']:
                if private.exists():
                    accounts = json.loads(private.read_text())
                    secret_values.extend(user['password'] for user in accounts['users'].values())
                    secret_values.extend(accounts[name] for name in ['bootstrap', 'appDatabase', 'identityDatabase'])
            if any(value in console for value in secret_values) or re.search(r'eyJ[A-Za-z0-9_-]+\.[A-Za-z0-9_-]+\.[A-Za-z0-9_-]+', console):
                raise SystemExit('CI credential exposure detected; console output suppressed')
            (LOCAL / 'jenkins-build-console.txt').write_text(console)
            provenance = checkout / 'frontend/.validation/ci/provenance.json'
            manifest = json.loads(provenance.read_text()) if provenance.exists() else {}
            if state['result'] == 'SUCCESS' and (manifest.get('commit') != revision or not manifest.get('complete') or manifest.get('cleanup') != 'passed'):
                raise SystemExit('Jenkins success did not contain complete pinned source/image/cleanup evidence')
            (LOCAL / 'jenkins-build-result.json').write_text(json.dumps({'build': number, 'result': state['result'],
                'commit': revision, 'tree': manifest.get('tree'), 'provenanceComplete': manifest.get('complete', False),
                'anonymousBlocked': True, 'credentialsExposed': False}, indent=2))
            print('Jenkins build', number, state['result'], '; credentials were not exposed', flush=True)
            sys.exit(0 if state['result'] == 'SUCCESS' else 1)
    except urllib.error.HTTPError as error:
        if error.code != 404:
            raise
    if attempt % 6 == 0:
        print('Waiting for CI build', number, flush=True)
    time.sleep(10)
raise SystemExit('CI build wait timed out; inspect local Jenkins')
