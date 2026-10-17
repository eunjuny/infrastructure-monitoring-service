package dev.infrastructure.monitoring.operations;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.net.URLDecoder;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import static org.junit.jupiter.api.Assertions.*;

class PrometheusOperationsTest {
    @Test void queriesOnlyFixedExpressionsAndPreservesMissingAndNonFiniteSamples() throws Exception {
        var requests=new ArrayList<String>();
        var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/api/v1/",exchange->{
            requests.add(URLDecoder.decode(exchange.getRequestURI().getRawQuery(),StandardCharsets.UTF_8));
            String body=exchange.getRequestURI().getPath().endsWith("query_range")
                    ? "{\"status\":\"success\",\"data\":{\"result\":[{\"metric\":{\"metric\":\"apiP95\"},\"values\":[[1,\"0.1\"],[2,\"NaN\"]]}]}}"
                    : "{\"status\":\"success\",\"data\":{\"result\":[{\"metric\":{\"metric\":\"up\"},\"value\":[1,\"1\"]},{\"metric\":{\"metric\":\"apiP95\"},\"value\":[1,\"NaN\"]}]}}";
            byte[] bytes=body.getBytes(StandardCharsets.UTF_8); exchange.sendResponseHeaders(200,bytes.length); exchange.getResponseBody().write(bytes); exchange.close();
        });
        server.start();
        try {
            var result=new PrometheusOperations("http://127.0.0.1:"+server.getAddress().getPort(),new ObjectMapper()).dashboard();
            assertTrue(result.available()); assertEquals(1.0,result.values().get("up")); assertNull(result.values().get("apiP95"));
            assertFalse(result.values().containsKey("heap")); assertNull(result.series().get("apiP95").get(1).value());
            assertEquals(2,requests.size()); assertTrue(requests.get(0).contains("infrastructure-api")); assertTrue(requests.get(1).contains("step=60"));
        } finally { server.stop(0); }
    }
    @Test void rejectsExternalRedirectableOrCredentialBearingEndpoints() {
        for(String endpoint:new String[]{"https://example.com","http://example.com","http://user:password@localhost","http://localhost?query=test"}) {
            assertThrows(IllegalArgumentException.class,()->new PrometheusOperations(endpoint,new ObjectMapper()));
        }
    }
    @Test void unavailableSourceDoesNotFallbackToDatabaseOrFakeZeroes() {
        var result=new PrometheusOperations("http://127.0.0.1:1",new ObjectMapper()).dashboard();
        assertFalse(result.available()); assertTrue(result.values().isEmpty()); assertTrue(result.series().isEmpty());
    }
    @Test void repeatedReadsKeepFixedQueriesAndBoundedSeries() throws Exception {
        var mapper = new ObjectMapper();
        var requests = new ArrayList<String>();
        var instant = mapper.createObjectNode();
        instant.put("status", "success");
        var vector = instant.putObject("data").putArray("result");
        for (String name : List.of("up", "unapproved")) {
            var row = vector.addObject();
            row.putObject("metric").put("metric", name);
            row.putArray("value").add(1).add("1");
        }
        var range = mapper.createObjectNode();
        range.put("status", "success");
        var matrix = range.putObject("data").putArray("result");
        for (String name : List.of("apiP95", "queryP95", "heap")) {
            var row = matrix.addObject();
            row.putObject("metric").put("metric", name);
            var points = row.putArray("values");
            for (int index = 0; index < 40; index++) {
                points.addArray().add(index).add(index == 1 ? "+Inf" : "0.25");
            }
        }
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/v1/", exchange -> {
            requests.add(URLDecoder.decode(exchange.getRequestURI().getRawQuery(), StandardCharsets.UTF_8));
            byte[] bytes = mapper.writeValueAsBytes(
                    exchange.getRequestURI().getPath().endsWith("query_range") ? range : instant);
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
        try {
            var operations = new PrometheusOperations("http://127.0.0.1:" + server.getAddress().getPort(), mapper);
            for (int iteration = 0; iteration < 2; iteration++) {
                var result = operations.dashboard();
                assertTrue(result.available());
                assertEquals(Set.of("up"), result.values().keySet());
                assertEquals(Set.of("apiP95", "queryP95"), result.series().keySet());
                for (var points : result.series().values()) {
                    assertEquals(31, points.size());
                    assertEquals(0, points.getFirst().timestamp());
                    assertEquals(30, points.getLast().timestamp());
                    assertEquals(0.25, points.getFirst().value());
                    assertNull(points.get(1).value());
                }
            }
            assertEquals(4, requests.size());
            assertEquals(requests.get(0), requests.get(2));
            var label = Pattern.compile(",\"metric\",\"([^\"]+)\",\"\",\"\"\\)");
            var names = label.matcher(requests.get(0)).results().map(match -> match.group(1)).toList();
            assertEquals(List.of("up", "requests", "apiP95", "queryP95", "errors", "heap",
                    "dbActive", "mockChanged", "mockFailures", "mockP95"), names);
            var rangeNames = label.matcher(requests.get(1)).results().map(match -> match.group(1)).toList();
            assertEquals(List.of("apiP95", "queryP95"), rangeNames);
            var window = Pattern.compile("&start=(\\d+)&end=(\\d+)&step=60$").matcher(requests.get(1));
            assertTrue(window.find());
            assertEquals(1800, Long.parseLong(window.group(2)) - Long.parseLong(window.group(1)));
            assertEquals(requests.get(1).split("&start=")[0], requests.get(3).split("&start=")[0]);
        } finally {
            server.stop(0);
        }
    }

    @Test void failedRangeReadDoesNotExposePartialInstantValues() throws Exception {
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/v1/", exchange -> {
            byte[] bytes = "{\"status\":\"success\",\"data\":{\"result\":[{\"metric\":{\"metric\":\"up\"},\"value\":[1,\"1\"]}]}}"
                    .getBytes(StandardCharsets.UTF_8);
            int status = exchange.getRequestURI().getPath().endsWith("query_range") ? 503 : 200;
            exchange.sendResponseHeaders(status, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
        try {
            var result = new PrometheusOperations("http://127.0.0.1:" + server.getAddress().getPort(),
                    new ObjectMapper()).dashboard();
            assertFalse(result.available());
            assertTrue(result.values().isEmpty());
            assertTrue(result.series().isEmpty());
        } finally {
            server.stop(0);
        }
    }
    @Test void oversizedResponsesAreRejected() throws Exception {
        var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/api/v1/",exchange->{
            byte[] bytes=new byte[1048577]; java.util.Arrays.fill(bytes,(byte)'x');
            exchange.sendResponseHeaders(200,bytes.length);
            try { exchange.getResponseBody().write(bytes); } catch(java.io.IOException ignored) { }
            finally { exchange.close(); }
        }); server.start();
        try { assertFalse(new PrometheusOperations("http://127.0.0.1:"+server.getAddress().getPort(),new ObjectMapper()).dashboard().available()); }
        finally { server.stop(0); }
    }
    @Test void deadlineIncludesStalledResponseBody() throws Exception {
        var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/api/v1/",exchange->{
            exchange.sendResponseHeaders(200,1000); exchange.getResponseBody().write('{'); exchange.getResponseBody().flush();
            try { Thread.sleep(5000); } catch(InterruptedException cause) { Thread.currentThread().interrupt(); }
            finally { exchange.close(); }
        }); server.start();
        try {
            long started=System.nanoTime();
            assertFalse(new PrometheusOperations("http://127.0.0.1:"+server.getAddress().getPort(),new ObjectMapper()).dashboard().available());
            assertTrue(java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-started)<4500);
        } finally { server.stop(0); }
    }
}
