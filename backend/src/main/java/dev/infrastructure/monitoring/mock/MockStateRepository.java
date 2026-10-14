package dev.infrastructure.monitoring.mock;

import dev.infrastructure.monitoring.resource.ResourceType;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import javax.sql.DataSource;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/** Only called by the opt-in local simulator; no HTTP mutation endpoint is exposed. */
@Repository
public class MockStateRepository {
    private final NamedParameterJdbcTemplate jdbc;

    public MockStateRepository(DataSource source, @Value("${monitoring.query-timeout-seconds:3}") int timeout) {
        if (timeout < 1) throw new IllegalArgumentException("Query timeout must be positive");
        jdbc = new NamedParameterJdbcTemplate(source);
        jdbc.getJdbcTemplate().setQueryTimeout(timeout);
    }

    public List<Long> nextIds(ResourceType type, long after, int limit) {
        if (after < 0 || limit < 1 || limit > 100) throw new IllegalArgumentException("Invalid mock batch");
        return jdbc.queryForList(selectionSql(type), Map.of("after", after, "limit", limit), Long.class);
    }

    public int change(ResourceType type, List<Long> ids, Instant changedAt) {
        if (ids.isEmpty()) return 0;
        if (ids.size() > 100 || ids.stream().anyMatch(id -> id == null || id <= 0)) {
            throw new IllegalArgumentException("Invalid mock IDs");
        }
        return jdbc.update(updateSql(type), new MapSqlParameterSource("ids", ids)
                .addValue("changedAt", Timestamp.from(changedAt)));
    }

    String selectionSql(ResourceType type) {
        return "select id from " + type.table()
                + " where id > :after order by id limit :limit for update";
    }

    String updateSql(ResourceType type) {
        String metrics = switch (type) {
            case NODE -> ", cpu_usage = least(100, mod(cpu_usage + 7, 101)),"
                    + " memory_usage = least(100, mod(memory_usage + 11, 101))";
            case POD -> ", restart_count = case when status = 'WARNING'"
                    + " then least(restart_count::bigint + 1, 2147483647)::integer else restart_count end";
            default -> "";
        };
        return "update " + type.table()
                + " set status = case status when 'NORMAL' then 'WARNING'"
                + " when 'WARNING' then 'ERROR' else 'NORMAL' end, updated_at = :changedAt"
                + metrics + " where id in (:ids)";
    }
}
