package dev.infrastructure.monitoring.operations;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

@Service
public class PrometheusOperations {
    public record Point(long timestamp, Double value) { }
    public record Dashboard(boolean available, String source, Instant fetchedAt,
                            Map<String, Double> values, Map<String, List<Point>> series, String message) { }

    private static final String JOB = "job=\"infrastructure-api\"";
    private static final int RANGE_SECONDS = 1800;
    private static final int STEP_SECONDS = 60;
    private static final int MAX_SERIES_POINTS = 31;
    private static final Map<String, String> EXPRESSIONS = expressions();
    private static final Set<String> SERIES_METRICS = Set.of("apiP95", "queryP95");
    // The metric allowlists never change per request, so assemble and encode them once.
    private static final String INSTANT_QUERY = encodedExpression(EXPRESSIONS.keySet());
    private static final String RANGE_QUERY = encodedExpression(SERIES_METRICS);

    private final PrometheusClient client;

    public PrometheusOperations(@Value("${monitoring.prometheus-url:http://127.0.0.1:9094}") String base,
                                ObjectMapper mapper) {
        this.client = new PrometheusClient(base, mapper);
    }

    private static Map<String, String> expressions() {
        var values = new LinkedHashMap<String, String>();
        values.put("up", "max(up{" + JOB + "})");
        values.put("requests", "sum(increase(http_server_requests_seconds_count{"
                + JOB + ",uri=~\"/api/resources.*\"}[5m]))");
        values.put("apiP95", "histogram_quantile(0.95,sum by(le)(rate(http_server_requests_seconds_bucket{"
                + JOB + ",uri=~\"/api/resources.*\"}[5m])))");
        values.put("queryP95", "histogram_quantile(0.95,sum by(le)(rate(infrastructure_query_seconds_bucket{"
                + JOB + "}[5m])))");
        values.put("errors", "sum(increase(http_server_requests_seconds_count{"
                + JOB + ",uri=~\"/api/resources.*\",status=~\"5..\"}[5m]))"
                + " or (sum(increase(http_server_requests_seconds_count{"
                + JOB + ",uri=~\"/api/resources.*\"}[5m])) * 0)");
        values.put("heap", "sum(jvm_memory_used_bytes{" + JOB + ",area=\"heap\"})");
        values.put("dbActive", "sum(hikaricp_connections_active{" + JOB + "})");
        values.put("mockChanged", "sum(increase(infrastructure_mock_rows_changed_total{" + JOB + "}[5m]))");
        values.put("mockFailures", "sum(increase(infrastructure_mock_failures_total{" + JOB + "}[5m]))");
        values.put("mockP95", "histogram_quantile(0.95,sum by(le)(rate(infrastructure_mock_batch_seconds_bucket{"
                + JOB + "}[5m])))");
        return Collections.unmodifiableMap(values);
    }
    private static String encodedExpression(Set<String> names) {
        String expression = EXPRESSIONS.entrySet().stream()
                .filter(entry -> names.contains(entry.getKey()))
                .map(entry -> "label_replace((" + entry.getValue() + "),\"metric\",\""
                        + entry.getKey() + "\",\"\",\"\")")
                .collect(Collectors.joining(" or "));
        return URLEncoder.encode(expression, StandardCharsets.UTF_8);
    }

    private JsonNode request(String path) throws Exception {
        return client.data(path).path("result");
    }

    static Double number(JsonNode value) {
        try {
            double number = Double.parseDouble(value.asText());
            return Double.isFinite(number) ? number : null;
        } catch (NumberFormatException cause) {
            return null;
        }
    }

    public Dashboard dashboard() {
        try {
            var values = readValues(request("/api/v1/query?query=" + INSTANT_QUERY));
            long end = Instant.now().getEpochSecond();
            String rangePath = "/api/v1/query_range?query=" + RANGE_QUERY
                    + "&start=" + (end - RANGE_SECONDS) + "&end=" + end + "&step=" + STEP_SECONDS;
            var series = readSeries(request(rangePath));
            return new Dashboard(true, "Prometheus", Instant.now(), values, series,
                    "API·DB 조회·Mock 배치의 실제 서버 지표입니다.");
        } catch (Exception cause) {
            if (cause instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            return new Dashboard(false, "Prometheus", Instant.now(), Map.of(), Map.of(),
                    "Prometheus 연결을 확인해 주세요. DB 값으로 대체하지 않습니다.");
        }
    }

    private static Map<String, Double> readValues(JsonNode vector) {
        var values = new LinkedHashMap<String, Double>();
        for (var row : vector) {
            String name = metricName(row);
            if (EXPRESSIONS.containsKey(name)) {
                values.put(name, number(row.path("value").path(1)));
            }
        }
        return values;
    }

    private static Map<String, List<Point>> readSeries(JsonNode matrix) {
        var series = new LinkedHashMap<String, List<Point>>();
        for (var row : matrix) {
            String name = metricName(row);
            if (!SERIES_METRICS.contains(name)) {
                continue;
            }
            var points = new ArrayList<Point>();
            for (var point : row.path("values")) {
                if (points.size() >= MAX_SERIES_POINTS) {
                    break;
                }
                points.add(new Point(point.path(0).asLong(), number(point.path(1))));
            }
            series.put(name, List.copyOf(points));
        }
        return series;
    }

    private static String metricName(JsonNode row) {
        return row.path("metric").path("metric").asText();
    }
}
