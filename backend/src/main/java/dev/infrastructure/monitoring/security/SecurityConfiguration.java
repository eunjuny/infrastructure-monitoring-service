package dev.infrastructure.monitoring.security;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.web.client.RestTemplate;

import java.util.Collection;
import java.util.List;
import java.util.Map;

@Configuration
public class SecurityConfiguration {
    @Bean
    @ConditionalOnProperty(name = "monitoring.auth.enabled", havingValue = "false", matchIfMissing = true)
    SecurityFilterChain localSecurity(HttpSecurity http) throws Exception {
        // Loopback/isolated demonstration only; never a fallback from auth mode.
        return http.csrf(csrf -> csrf.disable()).sessionManagement(session ->
                session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(request -> request.anyRequest().permitAll()).build();
    }

    @Bean
    @ConditionalOnProperty(name = "monitoring.auth.enabled", havingValue = "true")
    SecurityFilterChain authenticatedSecurity(HttpSecurity http, JwtDecoder decoder,
            @Value("${monitoring.auth.audience:monitoring-api}") String audience) throws Exception {
        // APIs accept Bearer headers, not cookies; no server-side login session.
        return http.csrf(csrf -> csrf.disable()).sessionManagement(session ->
                session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(request -> request
                        .requestMatchers("/api/auth/config", "/actuator/health", "/actuator/prometheus").permitAll()
                        .requestMatchers("/api/operations/**").hasRole("ADMIN")
                        .requestMatchers("/api/**").hasAnyRole("VIEWER", "ADMIN")
                        .anyRequest().denyAll())
                .oauth2ResourceServer(server -> server.jwt(jwt -> jwt.decoder(decoder)
                        .jwtAuthenticationConverter(token -> authentication(token, audience))))
                .build();
    }

    @Bean
    @ConditionalOnProperty(name = "monitoring.auth.enabled", havingValue = "true")
    JwtDecoder jwtDecoder(@Value("${monitoring.auth.issuer}") String issuer,
                          @Value("${monitoring.auth.jwk-set-uri}") String jwks,
                          @Value("${monitoring.auth.audience:monitoring-api}") String audience) {
        var factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(3000);
        factory.setReadTimeout(3000);
        var decoder = NimbusJwtDecoder.withJwkSetUri(jwks).restOperations(new RestTemplate(factory)).build();
        decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(JwtValidators.createDefaultWithIssuer(issuer),
                token -> token.getAudience() != null && token.getAudience().contains(audience) ? OAuth2TokenValidatorResult.success()
                        : OAuth2TokenValidatorResult.failure(new OAuth2Error("invalid_token", "Invalid audience", null)),
                token -> token.getSubject() != null && !token.getSubject().isBlank() ? OAuth2TokenValidatorResult.success()
                        : OAuth2TokenValidatorResult.failure(new OAuth2Error("invalid_token", "Missing subject", null)),
                token -> accessClaims(token, audience)));
        return decoder;
    }

    private static OAuth2TokenValidatorResult accessClaims(Jwt token, String audience) {
        // Signature validation alone must not make an unbounded-lifetime or malformed token usable.
        if (token.getExpiresAt() == null || !stringListOrMissing(token.getClaims().get("groups"), 1000)) {
            return invalidClaims();
        }
        Object access = token.getClaims().get("resource_access");
        if (access != null) {
            if (!(access instanceof Map<?, ?> clients)) return invalidClaims();
            Object client = clients.get(audience);
            if (client != null && (!(client instanceof Map<?, ?> details)
                    || !stringListOrMissing(details.get("roles"), 1000))) {
                return invalidClaims();
            }
        }
        return OAuth2TokenValidatorResult.success();
    }

    private static boolean stringListOrMissing(Object value, int maximum) {
        return value == null || value instanceof List<?> values && values.size() <= maximum
                && values.stream().allMatch(item -> item instanceof String);
    }

    private static OAuth2TokenValidatorResult invalidClaims() {
        return OAuth2TokenValidatorResult.failure(new OAuth2Error("invalid_token", "Invalid access token claims", null));
    }

    static JwtAuthenticationToken authentication(Jwt token, String audience) {
        Map<String, Object> access = token.getClaimAsMap("resource_access");
        Object client = access == null ? null : access.get(audience);
        Object roles = client instanceof Map<?, ?> map ? map.get("roles") : null;
        var authorities = roles instanceof Collection<?> values ? values.stream()
                .filter(value -> "ADMIN".equals(value) || "VIEWER".equals(value))
                .map(value -> new SimpleGrantedAuthority("ROLE_" + value)).toList()
                : List.<SimpleGrantedAuthority>of();
        return new JwtAuthenticationToken(token, authorities, token.getSubject());
    }
}
