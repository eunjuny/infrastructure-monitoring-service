import jenkins.model.Jenkins
import hudson.security.HudsonPrivateSecurityRealm
import hudson.security.FullControlOnceLoggedInAuthorizationStrategy
import java.nio.file.Files
import java.nio.file.Path

def instance = Jenkins.get()
def secret = Files.readString(Path.of('/run/ci-secrets/password')).trim()
if (secret.length() < 24) { throw new IllegalStateException('Missing local CI administrator secret') }
def realm = new HudsonPrivateSecurityRealm(false)
realm.createAccount('ci-admin', secret)
instance.setSecurityRealm(realm)
def authorization = new FullControlOnceLoggedInAuthorizationStrategy()
authorization.setAllowAnonymousRead(false)
instance.setAuthorizationStrategy(authorization)
instance.setNumExecutors(0)
instance.save()
