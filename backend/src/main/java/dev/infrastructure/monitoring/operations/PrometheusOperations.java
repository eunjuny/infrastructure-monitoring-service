package dev.infrastructure.monitoring.operations;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;

@Service
public class PrometheusOperations {
    public record Point(long timestamp,Double value) { }
    public record Dashboard(boolean available,String source,Instant fetchedAt,Map<String,Double> values,Map<String,List<Point>> series,String message) { }
    private final PrometheusClient client;
    private static final String JOB="job=\"infrastructure-api\"";
    private static final Map<String,String> EXPRESSIONS=expressions();
    public PrometheusOperations(@Value("${monitoring.prometheus-url:http://127.0.0.1:9094}") String base,ObjectMapper mapper) {
        this.client = new PrometheusClient(base, mapper);
    }
    private static Map<String,String> expressions() {
        var values=new LinkedHashMap<String,String>();
        values.put("up","max(up{"+JOB+"})");
        values.put("requests","sum(increase(http_server_requests_seconds_count{"+JOB+",uri=~\"/api/resources.*\"}[5m]))");
        values.put("apiP95","histogram_quantile(0.95,sum by(le)(rate(http_server_requests_seconds_bucket{"+JOB+",uri=~\"/api/resources.*\"}[5m])))");
        values.put("queryP95","histogram_quantile(0.95,sum by(le)(rate(infrastructure_query_seconds_bucket{"+JOB+"}[5m])))");
        values.put("errors","sum(increase(http_server_requests_seconds_count{"+JOB+",uri=~\"/api/resources.*\",status=~\"5..\"}[5m])) or (sum(increase(http_server_requests_seconds_count{"+JOB+",uri=~\"/api/resources.*\"}[5m])) * 0)");
        values.put("heap","sum(jvm_memory_used_bytes{"+JOB+",area=\"heap\"})");
        values.put("dbActive","sum(hikaricp_connections_active{"+JOB+"})");
        values.put("mockChanged","sum(increase(infrastructure_mock_rows_changed_total{"+JOB+"}[5m]))");
        values.put("mockFailures","sum(increase(infrastructure_mock_failures_total{"+JOB+"}[5m]))");
        values.put("mockP95","histogram_quantile(0.95,sum by(le)(rate(infrastructure_mock_batch_seconds_bucket{"+JOB+"}[5m])))");
        return Collections.unmodifiableMap(values);
    }
    private static String expression(Set<String> names) {
        return EXPRESSIONS.entrySet().stream().filter(entry->names.contains(entry.getKey()))
                .map(entry->"label_replace(("+entry.getValue()+"),\"metric\",\""+entry.getKey()+"\",\"\",\"\")")
                .collect(java.util.stream.Collectors.joining(" or "));
    }
    private JsonNode request(String path) throws Exception {
        return client.data(path).path("result");
    }
    static Double number(JsonNode value) {
        try { double number=Double.parseDouble(value.asText()); return Double.isFinite(number)?number:null; }
        catch(NumberFormatException cause) { return null; }
    }
    public Dashboard dashboard() {
        try {
            var values=new LinkedHashMap<String,Double>(); var series=new LinkedHashMap<String,List<Point>>();
            var vector=request("/api/v1/query?query="+URLEncoder.encode(expression(EXPRESSIONS.keySet()),StandardCharsets.UTF_8));
            for(var row:vector) { String name=row.path("metric").path("metric").asText(); if(EXPRESSIONS.containsKey(name)) values.put(name,number(row.path("value").path(1))); }
            long end=Instant.now().getEpochSecond();
            var matrix=request("/api/v1/query_range?query="+URLEncoder.encode(expression(Set.of("apiP95","queryP95")),StandardCharsets.UTF_8)+"&start="+(end-1800)+"&end="+end+"&step=60");
            for(var row:matrix) {
                String name=row.path("metric").path("metric").asText(); if(!Set.of("apiP95","queryP95").contains(name)) continue;
                var points=new ArrayList<Point>(); for(var point:row.path("values")) { if(points.size()>=31) break; points.add(new Point(point.path(0).asLong(),number(point.path(1)))); }
                series.put(name,List.copyOf(points));
            }
            return new Dashboard(true,"Prometheus",Instant.now(),values,series,"API·DB 조회·Mock 배치의 실제 서버 지표입니다.");
        } catch(Exception cause) {
            if(cause instanceof InterruptedException) Thread.currentThread().interrupt();
            return new Dashboard(false,"Prometheus",Instant.now(),Map.of(),Map.of(),"Prometheus 연결을 확인해 주세요. DB 값으로 대체하지 않습니다.");
        }
    }
}
