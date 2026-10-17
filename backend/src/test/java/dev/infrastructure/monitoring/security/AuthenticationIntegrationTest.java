package dev.infrastructure.monitoring.security;

import com.nimbusds.jose.JOSEObjectType;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.MockMvcPrint;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.HttpHeaders;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import javax.sql.DataSource;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Real signed JWT/JWKS, Spring Security and PostgreSQL; not mock authentication. */
@SpringBootTest(properties = "monitoring.auth.enabled=true")
@AutoConfigureMockMvc(print = MockMvcPrint.NONE)
@Testcontainers
class AuthenticationIntegrationTest {
    private static boolean seeded;
    @Container static final PostgreSQLContainer<?> DB = new PostgreSQLContainer<>("postgres:17-alpine");
    static final RSAKey KEY;
    static final HttpServer JWKS;
    static final String ISSUER;
    static {
        try {
            KEY = new RSAKeyGenerator(2048).keyID("fixture-signing-key").generate();
            JWKS = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            JWKS.createContext("/jwks", exchange -> {
                byte[] bytes = new JWKSet(KEY.toPublicJWK()).toString().getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, bytes.length);
                try (var output = exchange.getResponseBody()) { output.write(bytes); }
            });
            JWKS.start();
            ISSUER = "http://127.0.0.1:" + JWKS.getAddress().getPort() + "/realms/fixture";
        } catch (Exception exception) { throw new ExceptionInInitializerError(exception); }
    }

    @DynamicPropertySource static void properties(DynamicPropertyRegistry properties) {
        properties.add("spring.datasource.url", DB::getJdbcUrl);
        properties.add("spring.datasource.username", DB::getUsername);
        properties.add("spring.datasource.password", DB::getPassword);
        properties.add("monitoring.auth.issuer", () -> ISSUER);
        properties.add("monitoring.auth.jwk-set-uri", () -> "http://127.0.0.1:" + JWKS.getAddress().getPort() + "/jwks");
    }
    @Autowired DataSource dataSource;
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;

    @BeforeEach void seed() {
        if (!seeded) {
            new ResourceDatabasePopulator(new ClassPathResource("seed.sql")).execute(dataSource);
            seeded = true;
        }
    }
    @AfterAll static void stopJwks() { JWKS.stop(0); }

    String token(String subject, String role, List<String> groups) throws Exception {
        return sign(KEY, ISSUER, "monitoring-api", Instant.now().plusSeconds(120), subject,
                Map.of("monitoring-api", Map.of("roles", List.of(role))), groups);
    }
    String sign(RSAKey key, String issuer, String audience, Instant expiry, String subject,
                Map<String, Object> roles, List<String> groups) throws Exception {
        var claims = new JWTClaimsSet.Builder().issuer(issuer).subject(subject).audience(audience)
                .issueTime(Date.from(Instant.now().minusSeconds(5))).expirationTime(expiry == null ? null : Date.from(expiry))
                .claim("preferred_username", "admin") // Name must never grant administrator access.
                .claim("resource_access", roles).claim("groups", groups).build();
        var signed = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.RS256).type(JOSEObjectType.JWT)
                .keyID(KEY.getKeyID()).build(), claims);
        signed.sign(new RSASSASigner(key));
        return signed.serialize();
    }
    String viewer(boolean a) throws Exception {
        return token(a ? "subject-a" : "subject-b", "VIEWER", IntStream.rangeClosed(a ? 1 : 11, a ? 10 : 20)
                .mapToObj(id -> "cluster-" + id).toList());
    }

    @Test void anonymousApiIsDeniedButConfigurationIsPublic() throws Exception {
        mvc.perform(get("/api/resources/summary")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/auth/config")).andExpect(status().isOk()).andExpect(jsonPath("$.enabled").value(true));
    }
    @Test void administratorSeesAllDataWithoutAssignments() throws Exception {
        String admin = token("subject-admin", "ADMIN", List.of());
        mvc.perform(get("/api/resources/summary").header(HttpHeaders.AUTHORIZATION, "Bearer " + admin))
                .andExpect(status().isOk()).andExpect(jsonPath("$.clusters").value(20))
                .andExpect(jsonPath("$.pods").value(150000));
        mvc.perform(get("/api/resources/POD/50001").header(HttpHeaders.AUTHORIZATION, "Bearer " + admin))
                .andExpect(status().isOk());
    }

    @Test void diagnosticAggregatesKeepTheSameClusterOwnershipBoundary() throws Exception {
        String first = "Bearer " + viewer(true);
        String second = "Bearer " + viewer(false);
        mvc.perform(get("/api/resources/CLUSTER/1").header(HttpHeaders.AUTHORIZATION, first))
                .andExpect(status().isOk()).andExpect(jsonPath("$.attributes.podCount").value(10000));
        mvc.perform(get("/api/resources/CLUSTER/11").header(HttpHeaders.AUTHORIZATION, second))
                .andExpect(status().isOk()).andExpect(jsonPath("$.attributes.podCount").value(5000));
        mvc.perform(get("/api/resources/NAMESPACE/1").header(HttpHeaders.AUTHORIZATION, first))
                .andExpect(status().isOk()).andExpect(jsonPath("$.attributes.podCount").value(100));
        mvc.perform(get("/api/resources/CLUSTER/11").header(HttpHeaders.AUTHORIZATION, first)).andExpect(status().isNotFound());
        mvc.perform(get("/api/resources/NAMESPACE/1").header(HttpHeaders.AUTHORIZATION, second)).andExpect(status().isNotFound());
        mvc.perform(get("/api/resources/POD/50001").header(HttpHeaders.AUTHORIZATION, first)).andExpect(status().isNotFound());
    }
    @Test void eachViewerHasItsAssignedCountsAndDisjointClusterLists() throws Exception {
        for (boolean a : List.of(true, false)) {
            String bearer = "Bearer " + viewer(a);
            mvc.perform(get("/api/resources/summary").header(HttpHeaders.AUTHORIZATION, bearer))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.clusters").value(10))
                    .andExpect(jsonPath("$.nodes").value(100)).andExpect(jsonPath("$.namespaces").value(1000))
                    .andExpect(jsonPath("$.pods").value(a ? 100000 : 50000));
            mvc.perform(get("/api/resources").header(HttpHeaders.AUTHORIZATION, bearer))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.items.length()").value(10))
                    .andExpect(jsonPath("$.items[0].id").value(a ? 1 : 11))
                    .andExpect(jsonPath("$.items[9].id").value(a ? 10 : 20));
        }
    }
    @Test void crossClusterDetailPathChildrenScopeAndHistoryAreHidden() throws Exception {
        for (boolean a : List.of(true, false)) {
            long pod = a ? 50001 : 1;
            String bearer = "Bearer " + viewer(a);
            for (String suffix : List.of("", "/path", "/events")) {
                mvc.perform(get("/api/resources/POD/" + pod + suffix).header(HttpHeaders.AUTHORIZATION, bearer))
                        .andExpect(status().isNotFound());
            }
            mvc.perform(get("/api/resources/CLUSTER/" + (a ? 11 : 1) + "/children")
                    .header(HttpHeaders.AUTHORIZATION, bearer)).andExpect(status().isNotFound());
            mvc.perform(get("/api/resources?type=POD&clusterId=" + (a ? 11 : 1))
                    .header(HttpHeaders.AUTHORIZATION, bearer)).andExpect(status().isNotFound());
        }
    }

    @Test void appendedIdsFollowClusterAssignmentsRatherThanOldNumericHalfRanges() throws Exception {
        for (boolean a : List.of(true, false)) {
            String bearer = "Bearer " + viewer(a);
            long own = a ? 150000 : 50001;
            long other = a ? 50001 : 150000;
            mvc.perform(get("/api/resources/POD/" + own).header(HttpHeaders.AUTHORIZATION, bearer))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.path[0].id").value(a ? 10 : 11));
            mvc.perform(get("/api/resources/POD/" + other).header(HttpHeaders.AUTHORIZATION, bearer))
                    .andExpect(status().isNotFound());
            mvc.perform(get("/api/resources/POD/" + other + "/events").header(HttpHeaders.AUTHORIZATION, bearer))
                    .andExpect(status().isNotFound());
        }
    }
    @Test void globalSearchIsScopedAndCursorCannotCrossUsers() throws Exception {
        mvc.perform(get("/api/resources/search?q=cluster-&type=CLUSTER").header(HttpHeaders.AUTHORIZATION, "Bearer " + viewer(false)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.items.length()").value(10))
                .andExpect(jsonPath("$.items[0].resource.id").value(11));
        String body = mvc.perform(get("/api/resources?type=POD").header(HttpHeaders.AUTHORIZATION, "Bearer " + viewer(true)))
                .andReturn().getResponse().getContentAsString();
        String cursor = mapper.readTree(body).path("nextCursor").asText();
        mvc.perform(get("/api/resources?type=POD").param("cursor", cursor)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + viewer(false))).andExpect(status().isBadRequest());
    }
    @Test void operationsRequiresAdministratorAndNamesDoNotGrantIt() throws Exception {
        mvc.perform(get("/api/operations/alert-email")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/operations/alert-email").header(HttpHeaders.AUTHORIZATION, "Bearer " + viewer(true)))
                .andExpect(status().isForbidden());
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put("/api/operations/alert-email")
                .contentType("application/json").content("{\"recipient\":\"admin@example.invalid\",\"enabled\":false}")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + viewer(false))).andExpect(status().isForbidden());
        mvc.perform(get("/api/operations/alerts").header(HttpHeaders.AUTHORIZATION, "Bearer " + viewer(true)))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/operations/alerts")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/operations/metrics").header(HttpHeaders.AUTHORIZATION, "Bearer " + viewer(true)))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/auth/me").header(HttpHeaders.AUTHORIZATION, "Bearer " + viewer(true)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.administrator").value(false));
    }
    @Test void viewerWithoutAssignmentsGetsNothingNotGlobalAccess() throws Exception {
        String bearer = "Bearer " + token("unassigned", "VIEWER", List.of("other", "cluster-0"));
        mvc.perform(get("/api/resources/summary").header(HttpHeaders.AUTHORIZATION, bearer))
                .andExpect(status().isOk()).andExpect(jsonPath("$.pods").value(0));
        mvc.perform(get("/api/resources?type=POD").header(HttpHeaders.AUTHORIZATION, bearer))
                .andExpect(status().isOk()).andExpect(jsonPath("$.items.length()").value(0));
    }
    @Test void invalidIssuerAudienceExpiryAndSignatureAreRejected() throws Exception {
        var role = Map.<String, Object>of("monitoring-api", Map.of("roles", List.of("ADMIN")));
        var now = Instant.now();
        RSAKey wrong = new RSAKeyGenerator(2048).keyID(KEY.getKeyID()).generate();
        for (String bearer : List.of(
                sign(KEY, ISSUER + "-other", "monitoring-api", now.plusSeconds(120), "x", role, List.of()),
                sign(KEY, ISSUER, "other-api", now.plusSeconds(120), "x", role, List.of()),
                sign(KEY, ISSUER, "monitoring-api", now.minusSeconds(300), "x", role, List.of()),
                sign(KEY, ISSUER, "monitoring-api", now.plusSeconds(120), null, role, List.of()),
                sign(wrong, ISSUER, "monitoring-api", now.plusSeconds(120), "x", role, List.of()))) {
            mvc.perform(get("/api/resources/summary").header(HttpHeaders.AUTHORIZATION, "Bearer " + bearer))
                    .andExpect(status().isUnauthorized());
        }
    }
    @Test void rolesForAnotherClientDoNotGrantAccess() throws Exception {
        String jwt = sign(KEY, ISSUER, "monitoring-api", Instant.now().plusSeconds(120), "x",
                Map.of("other-api", Map.of("roles", List.of("ADMIN"))), List.of());
        mvc.perform(get("/api/resources/summary").header(HttpHeaders.AUTHORIZATION, "Bearer " + jwt))
                .andExpect(status().isForbidden());
    }

    @Test void signedTokenWithoutExpirationIsRejected() throws Exception {
        String jwt = sign(KEY, ISSUER, "monitoring-api", null, "no-expiration",
                Map.of("monitoring-api", Map.of("roles", List.of("ADMIN"))), List.of());
        mvc.perform(get("/api/resources/summary").header(HttpHeaders.AUTHORIZATION, "Bearer " + jwt))
                .andExpect(status().isUnauthorized());
    }

    @Test void futureNotBeforeAndMissingAudienceAreRejected() throws Exception {
        var valid = new JWTClaimsSet.Builder().issuer(ISSUER).audience("monitoring-api").subject("time-audience")
                .expirationTime(Date.from(Instant.now().plusSeconds(600)))
                .claim("resource_access", Map.of("monitoring-api", Map.of("roles", List.of("ADMIN")))).build();
        for (var invalid : List.of(
                new JWTClaimsSet.Builder(valid).audience((String) null).build(),
                new JWTClaimsSet.Builder(valid).notBeforeTime(Date.from(Instant.now().plusSeconds(300))).build())) {
            var signed = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(KEY.getKeyID()).build(), invalid);
            signed.sign(new RSASSASigner(KEY));
            mvc.perform(get("/api/resources/summary").header(HttpHeaders.AUTHORIZATION, "Bearer " + signed.serialize()))
                    .andExpect(status().isUnauthorized());
        }
    }

    @Test void malformedGroupAndRoleClaimsAreRejectedBeforeAnyDataAccess() throws Exception {
        var valid = new JWTClaimsSet.Builder().issuer(ISSUER).audience("monitoring-api").subject("malformed-claims")
                .expirationTime(Date.from(Instant.now().plusSeconds(120)))
                .claim("resource_access", Map.of("monitoring-api", Map.of("roles", List.of("VIEWER"))))
                .claim("groups", List.of("cluster-1")).build();
        for (var malformed : List.of(
                new JWTClaimsSet.Builder(valid).claim("groups", Map.of("unexpected", "cluster-1")).build(),
                new JWTClaimsSet.Builder(valid).claim("groups", List.of("cluster-1", 2)).build(),
                new JWTClaimsSet.Builder(valid).claim("resource_access", "ADMIN").build(),
                new JWTClaimsSet.Builder(valid).claim("resource_access", Map.of("monitoring-api", Map.of("roles", "ADMIN"))).build())) {
            var signed = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(KEY.getKeyID()).build(), malformed);
            signed.sign(new RSASSASigner(KEY));
            mvc.perform(get("/api/resources/summary").header(HttpHeaders.AUTHORIZATION, "Bearer " + signed.serialize()))
                    .andExpect(status().isUnauthorized());
        }
    }
}
