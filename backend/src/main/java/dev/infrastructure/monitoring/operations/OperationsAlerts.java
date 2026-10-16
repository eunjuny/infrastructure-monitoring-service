package dev.infrastructure.monitoring.operations;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Service
public class OperationsAlerts {
    public record Rule(String name, String title, String severity, String state, String activeAt,
                       Double value, String condition, String response) { }
    public record Snapshot(boolean available, String source, Instant fetchedAt, List<Rule> rules, String message) { }
    private record Policy(String title, String severity, String condition, String response) { }
    private static final Map<String, Policy> POLICIES = policies();
    private final PrometheusClient client;

    public OperationsAlerts(@Value("${monitoring.prometheus-url:http://127.0.0.1:9094}") String base, ObjectMapper mapper) {
        client = new PrometheusClient(base, mapper);
    }

    private static Map<String, Policy> policies() {
        var values = new LinkedHashMap<String, Policy>();
        values.put("BackendScrapeUnavailable", new Policy("백엔드 지표 수집 실패", "critical", "수집 실패 또는 대상 시계열 없음이 1분 지속",
                "백엔드 상태·관리 포트·수집 경로를 확인합니다. DB 장애로 단정하지 않습니다."));
        values.put("ResourceApiErrorBurst", new Policy("리소스 API 오류 증가", "warning", "최근 5분 요청 20건 이상·5xx 비율 10% 초과가 1분 지속",
                "최근 배포와 서버 오류·DB 연결을 확인합니다. 401·403은 이 경보의 오류에 포함하지 않습니다."));
        values.put("MockBatchFailures", new Policy("Mock 상태 갱신 반복 실패", "warning", "최근 5분 배치 실패 3회 이상이 1분 지속",
                "DB 연결·트랜잭션 실패 원인을 확인하고 복구 후 배치 성공을 확인합니다."));
        return java.util.Collections.unmodifiableMap(values);
    }

    public Snapshot snapshot() {
        try {
            var groups = client.data("/api/v1/rules?type=alert").path("groups");
            if (!groups.isArray() || groups.size() > 32) throw new IllegalStateException("Invalid rule groups");
            var found = new LinkedHashMap<String, Rule>();
            for (var group : groups) {
                var rules = group.path("rules");
                if (!rules.isArray() || rules.size() > 100) throw new IllegalStateException("Invalid rules");
                for (var rule : rules) {
                    String name = rule.path("name").asText();
                    var policy = POLICIES.get(name);
                    if (policy == null) continue;
                    String state = rule.path("state").asText();
                    if (!"alerting".equals(rule.path("type").asText()) || !"ok".equals(rule.path("health").asText())
                            || !Set.of("inactive", "pending", "firing").contains(state) || found.containsKey(name)) {
                        throw new IllegalStateException("Rule evaluation unavailable");
                    }
                    var alerts = rule.path("alerts");
                    if (!alerts.isArray() || alerts.size() > 100) throw new IllegalStateException("Invalid alert samples");
                    JsonNode sample = null;
                    for (var alert : alerts) {
                        if (state.equals(alert.path("state").asText())) { sample = alert; break; }
                    }
                    if (!"inactive".equals(state) && sample == null) throw new IllegalStateException("Missing alert sample");
                    String activeAt = sample == null ? null : Instant.parse(sample.path("activeAt").asText()).toString();
                    Double value = sample == null ? null : PrometheusOperations.number(sample.path("value"));
                    found.put(name, new Rule(name, policy.title, policy.severity, state, activeAt, value, policy.condition, policy.response));
                }
            }
            if (found.size() != POLICIES.size()) throw new IllegalStateException("Required rules not loaded");
            var result = new ArrayList<Rule>();
            POLICIES.keySet().forEach(name -> result.add(found.get(name)));
            return new Snapshot(true, "Prometheus", Instant.now(), List.copyOf(result),
                    "현재 경보 조건입니다. 알림 전달은 Alertmanager의 별도 수신 경로를 사용합니다.");
        } catch (Exception cause) {
            if (cause instanceof InterruptedException) Thread.currentThread().interrupt();
            return new Snapshot(false, "Prometheus", Instant.now(), List.of(),
                    "경보 상태 확인 불가: Prometheus 연결·규칙 로드·평가 상태를 확인해 주세요.");
        }
    }
}
