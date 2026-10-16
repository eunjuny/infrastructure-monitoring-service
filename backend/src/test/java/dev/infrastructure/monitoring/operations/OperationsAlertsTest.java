package dev.infrastructure.monitoring.operations;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class OperationsAlertsTest {
    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();

    private List<Map<String, Object>> rules() {
        var rules = new ArrayList<Map<String, Object>>();
        for (String name : List.of("BackendScrapeUnavailable", "ResourceApiErrorBurst", "MockBatchFailures")) {
            rules.add(new LinkedHashMap<>(Map.of("name", name, "type", "alerting", "state", "inactive", "health", "ok", "alerts", List.of())));
        }
        return rules;
    }

    private OperationsAlerts.Snapshot snapshot(List<Map<String, Object>> rules) throws Exception {
        byte[] bytes = mapper.writeValueAsBytes(Map.of("status", "success", "data", Map.of("groups", List.of(Map.of("rules", rules)))));
        var paths = new ArrayList<String>();
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/v1/rules", exchange -> {
            paths.add(exchange.getRequestURI().toString());
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
        try {
            var result = new OperationsAlerts("http://127.0.0.1:" + server.getAddress().getPort(), mapper).snapshot();
            assertEquals(List.of("/api/v1/rules?type=alert"), paths);
            return result;
        } finally { server.stop(0); }
    }

    @Test void showsActualPendingFiringAndInactiveRulesWithoutEchoingAnnotations() throws Exception {
        var rules = rules();
        rules.get(0).putAll(Map.of("state", "firing", "annotations", Map.of("summary", "untrusted"),
                "alerts", List.of(Map.of("state", "firing", "activeAt", "2026-10-15T00:00:00Z", "value", "0"))));
        rules.get(1).putAll(Map.of("state", "pending", "alerts", List.of(Map.of("state", "pending", "activeAt", "2026-10-15T00:00:00Z", "value", "0.2"))));
        var result = snapshot(rules);
        assertTrue(result.available());
        assertEquals(List.of("firing", "pending", "inactive"), result.rules().stream().map(OperationsAlerts.Rule::state).toList());
        assertEquals(0.2, result.rules().get(1).value());
        assertNull(result.rules().get(2).activeAt());
        assertFalse(mapper.writeValueAsString(result).contains("untrusted"));
    }

    @Test void missingRulesAreUnavailableNotHealthy() throws Exception {
        assertFalse(snapshot(rules().subList(0, 2)).available());
    }

    @Test void unhealthyDuplicateAndMalformedRulesAreUnavailable() throws Exception {
        List<Consumer<List<Map<String, Object>>>> mutations = List.of(
                rows -> rows.get(0).put("health", "err"),
                rows -> rows.add(rows.get(0)),
                rows -> rows.get(0).put("state", "unknown"),
                rows -> rows.get(0).put("state", "firing"),
                rows -> rows.get(0).putAll(Map.of("state", "pending", "alerts", List.of(Map.of("state", "pending", "activeAt", "invalid", "value", "1")))));
        for (var mutation : mutations) {
            var rows = rules();
            mutation.accept(rows);
            assertFalse(snapshot(rows).available());
        }
    }

    @Test void connectionFailureHasNoFakeInactiveRules() {
        var result = new OperationsAlerts("http://127.0.0.1:1", mapper).snapshot();
        assertFalse(result.available());
        assertTrue(result.rules().isEmpty());
    }

    @Test void refusesExternalEndpointsAndConfiguredPaths() {
        for (String endpoint : List.of("http://example.invalid", "http://localhost/private", "http://user:password@localhost")) {
            assertThrows(IllegalArgumentException.class, () -> new OperationsAlerts(endpoint, mapper));
        }
    }

    @Test void redirectsAreNotFollowed() throws Exception {
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/v1/rules", exchange -> {
            exchange.getResponseHeaders().set("Location", "http://example.invalid/");
            exchange.sendResponseHeaders(302, -1);
            exchange.close();
        });
        server.start();
        try { assertFalse(new OperationsAlerts("http://127.0.0.1:" + server.getAddress().getPort(), mapper).snapshot().available()); }
        finally { server.stop(0); }
    }
}
