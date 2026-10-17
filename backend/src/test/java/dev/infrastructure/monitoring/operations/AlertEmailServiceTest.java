package dev.infrastructure.monitoring.operations;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;
import static org.junit.jupiter.api.Assertions.*;

class AlertEmailServiceTest {
    private HttpServer server;
    private final AtomicInteger calls = new AtomicInteger();
    private final String token = "fixture-control-token-not-a-secret";

    AlertEmailService client(int status, String body) throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/email-settings", exchange -> {
            calls.incrementAndGet();
            if (!("Bearer " + token).equals(exchange.getRequestHeaders().getFirst("Authorization"))) {
                exchange.sendResponseHeaders(403, -1);
            } else {
                byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(status, bytes.length);
                exchange.getResponseBody().write(bytes);
            }
            exchange.close();
        });
        server.start();
        return new AlertEmailService(new ObjectMapper(), "http://127.0.0.1:" + server.getAddress().getPort(), token);
    }

    @AfterEach void stop() { if (server != null) server.stop(0); }

    @Test void proxiesBoundedSettingsWithoutSmtpCredentials() throws Exception {
        var service = client(200, "{\"recipient\":\"admin@example.invalid\",\"enabled\":false,\"smtpConfigured\":false,\"available\":true}");
        assertEquals("admin@example.invalid", service.read().recipient());
        assertFalse(service.update(new AlertEmailService.Settings("admin@example.invalid", false)).enabled());
        assertEquals(2, calls.get());
    }

    @Test void rejectsInjectionAndMultipleRecipientsBeforeCallingReceiver() throws Exception {
        var service = client(200, "{}");
        for (String address : new String[]{"x@example.invalid\r\nBcc: x@example.invalid", "x@example.invalid,y@example.invalid", "Name <x@example.invalid>", "not-mail"}) {
            assertEquals(400, assertThrows(ResponseStatusException.class,
                    () -> service.update(new AlertEmailService.Settings(address, false))).getStatusCode().value());
        }
        assertEquals(0, calls.get());
    }

    @Test void unavailableOrRejectedSettingsNeverReturnSuccess() throws Exception {
        var service = client(503, "private diagnostic must not escape");
        var failure = assertThrows(ResponseStatusException.class, service::read);
        assertEquals(503, failure.getStatusCode().value());
        assertFalse(failure.getMessage().contains("private diagnostic"));
    }

    @Test void missingControlTokenAndExternalEndpointsAreRejected() {
        var service = new AlertEmailService(new ObjectMapper(), "http://127.0.0.1:1", "");
        assertThrows(ResponseStatusException.class, service::read);
        for (String url : new String[]{"https://example.invalid", "http://example.invalid", "http://user@localhost", "http://localhost/path"}) {
            assertThrows(IllegalArgumentException.class, () -> new AlertEmailService(new ObjectMapper(), url, token));
        }
    }
}
