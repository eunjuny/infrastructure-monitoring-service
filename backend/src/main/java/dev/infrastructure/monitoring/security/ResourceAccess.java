package dev.infrastructure.monitoring.security;

import dev.infrastructure.monitoring.resource.ResourceType;
import jakarta.persistence.TypedQuery;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;
import java.util.List;
import java.util.TreeSet;

@Component
public class ResourceAccess {
    public record Scope(boolean all, List<Long> clusters) { }
    private final boolean enabled;

    public ResourceAccess(@Value("${monitoring.auth.enabled:false}") boolean enabled) {
        this.enabled = enabled;
    }

    public Scope scope() {
        if (!enabled) return new Scope(true, List.of());
        var authentication = SecurityContextHolder.getContext().getAuthentication();
        if (!(authentication instanceof JwtAuthenticationToken token) || !token.isAuthenticated()) {
            throw new AccessDeniedException("Authenticated resource access is required");
        }
        if (token.getAuthorities().stream().anyMatch(role -> role.getAuthority().equals("ROLE_ADMIN"))) {
            return new Scope(true, List.of());
        }
        if (token.getAuthorities().stream().noneMatch(role -> role.getAuthority().equals("ROLE_VIEWER"))) {
            throw new AccessDeniedException("Resource role is required");
        }
        List<String> groups = token.getToken().getClaimAsStringList("groups");
        var clusters = new TreeSet<Long>();
        if (groups != null) {
            if (groups.size() > 1000) throw new AccessDeniedException("Too many cluster assignments");
            for (String group : groups) {
                if (group != null && group.matches("cluster-[1-9][0-9]{0,17}")) {
                    clusters.add(Long.parseLong(group.substring(8)));
                }
            }
        }
        return new Scope(false, List.copyOf(clusters));
    }

    public String predicate(ResourceType type) {
        Scope scope = scope();
        if (scope.all()) return "";
        if (scope.clusters().isEmpty()) return "1 = 0";
        return clusterPath(type) + " in :accessibleClusters";
    }

    public <T> TypedQuery<T> bind(TypedQuery<T> query) {
        Scope scope = scope();
        if (!scope.all() && !scope.clusters().isEmpty()) query.setParameter("accessibleClusters", scope.clusters());
        return query;
    }

    public String cursorIdentity() {
        if (!enabled) return "";
        Scope scope = scope();
        var token = (JwtAuthenticationToken) SecurityContextHolder.getContext().getAuthentication();
        String identity = token.getToken().getIssuer() + "\0" + token.getToken().getSubject()
                + "\0" + scope.all() + "\0" + scope.clusters();
        try {
            return Base64.getUrlEncoder().withoutPadding().encodeToString(
                    MessageDigest.getInstance("SHA-256").digest(identity.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private String clusterPath(ResourceType type) {
        return switch (type) {
            case CLUSTER -> "r.id";
            case NODE -> "r.cluster.id";
            case NAMESPACE -> "r.node.cluster.id";
            case POD -> "r.namespace.node.cluster.id";
        };
    }
}
