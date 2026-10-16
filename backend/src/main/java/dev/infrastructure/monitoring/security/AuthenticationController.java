package dev.infrastructure.monitoring.security;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
public class AuthenticationController {
    private final ResourceAccess access;
    private final boolean enabled;
    private final String issuer;

    public AuthenticationController(ResourceAccess access,
            @Value("${monitoring.auth.enabled:false}") boolean enabled,
            @Value("${monitoring.auth.issuer:}") String issuer) {
        this.access = access;
        this.enabled = enabled;
        this.issuer = issuer;
    }

    public record Configuration(boolean enabled, String issuer, String clientId) { }
    public record User(String name, boolean administrator, List<Long> clusters) { }

    @GetMapping("/api/auth/config")
    public Configuration configuration() {
        return new Configuration(enabled, issuer, "monitoring-web");
    }

    @GetMapping("/api/auth/me")
    public User me() {
        var scope = access.scope();
        var authentication = SecurityContextHolder.getContext().getAuthentication();
        String name = authentication instanceof JwtAuthenticationToken token
                ? token.getToken().getClaimAsString("preferred_username") : "local";
        return new User(name, enabled && scope.all(), scope.clusters());
    }
}
