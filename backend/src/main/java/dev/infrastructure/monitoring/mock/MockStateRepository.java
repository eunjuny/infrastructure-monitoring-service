package dev.infrastructure.monitoring.mock;

import dev.infrastructure.monitoring.resource.ResourceType;
import org.springframework.beans.factory.annotation.Value;
import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;

/** Only called by the opt-in local simulator; no HTTP mutation endpoint is exposed. */
@Repository
@Transactional(propagation = Propagation.MANDATORY)
public class MockStateRepository {
    private final EntityManager entityManager;
    private final int timeoutMillis;

    public MockStateRepository(EntityManager entityManager, @Value("${monitoring.query-timeout-seconds:3}") int timeout) {
        if (timeout < 1) throw new IllegalArgumentException("Query timeout must be positive");
        this.entityManager = entityManager;
        timeoutMillis = Math.multiplyExact(timeout, 1000);
    }

    public List<Long> nextIds(ResourceType type, long after, int limit) {
        if (after < 0 || limit < 1 || limit > 100) throw new IllegalArgumentException("Invalid mock batch");
        List<?> rows = query(selectionSql(type)).setParameter("after", after).setParameter("limit", limit).getResultList();
        return rows.stream().map(value -> ((Number) value).longValue()).toList();
    }

    public int change(ResourceType type, List<Long> ids, Instant changedAt) {
        if (ids.isEmpty()) return 0;
        if (ids.size() > 100 || ids.stream().anyMatch(id -> id == null || id <= 0)) {
            throw new IllegalArgumentException("Invalid mock IDs");
        }
        String attributes = switch (type) {
            case NODE -> "jsonb_build_object('cpuUsage',cpu_usage,'memoryUsage',memory_usage)";
            case POD -> "jsonb_build_object('restartCount',restart_count)";
            default -> "'{}'::jsonb";
        };
        String statement = "with previous as materialized (select id,status," + attributes
                + " attributes from " + type.table() + " where id in (:ids) for update), changed as ("
                + updateSql(type) + " and id in (select id from previous) returning id,status," + attributes + " attributes)"
                + " insert into resource_change_event(resource_type,resource_id,occurred_at,previous_status,next_status,source,before_attributes,after_attributes)"
                + " select '" + type.name() + "',changed.id,:changedAt,previous.status,changed.status,'MOCK_SIMULATOR',previous.attributes,changed.attributes"
                + " from changed join previous on previous.id=changed.id";
        // Keep DB-specific update + event insertion atomic; never mix bulk DML with stale managed entities.
        entityManager.flush();
        int changed = query(statement).unwrap(org.hibernate.query.NativeQuery.class)
                .setParameterList("ids", ids).setParameter("changedAt", Timestamp.from(changedAt)).executeUpdate();
        entityManager.clear();
        return changed;
    }

    private Query query(String sql) {
        return entityManager.createNativeQuery(sql).setHint("jakarta.persistence.query.timeout", timeoutMillis);
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
