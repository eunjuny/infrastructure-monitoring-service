package dev.infrastructure.monitoring.resource;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/resources")
public class ResourceSummaryController {
    private final JdbcTemplate jdbc;

    public ResourceSummaryController(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @GetMapping("/summary")
    public ResourceSummary summary() {
        return jdbc.queryForObject("""
                select
                  (select count(*) from infrastructure_cluster) as clusters,
                  (select count(*) from infrastructure_node) as nodes,
                  (select count(*) from infrastructure_namespace) as namespaces,
                  (select count(*) from infrastructure_pod) as pods
                """, (rs, row) -> new ResourceSummary(
                rs.getLong("clusters"), rs.getLong("nodes"),
                rs.getLong("namespaces"), rs.getLong("pods")));
    }

    public record ResourceSummary(long clusters, long nodes, long namespaces, long pods) { }
}
