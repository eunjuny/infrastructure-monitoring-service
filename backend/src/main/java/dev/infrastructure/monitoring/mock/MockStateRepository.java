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
        if (type == ResourceType.POD) updateContainerScenarios(ids);
        updateKubernetesEvents(type, ids, changedAt);
        entityManager.clear();
        return changed;
    }

    private void updateContainerScenarios(List<Long> ids) {
        query("update infrastructure_pod_container c set state = case"
                + " when p.status = 'ERROR' and p.id % 2 = 0 then 'WAITING'"
                + " when p.status = 'ERROR' and p.id % 2 = 1 then 'TERMINATED'"
                + " when p.status = 'WARNING' then 'WAITING' else 'RUNNING' end,"
                + " ready = (p.status = 'NORMAL'), restart_count = greatest(c.restart_count, p.restart_count)"
                + " from infrastructure_pod p where c.pod_id = p.id and p.id in (:ids)")
                .unwrap(org.hibernate.query.NativeQuery.class).setParameterList("ids", ids).executeUpdate();
    }

    private void updateKubernetesEvents(ResourceType type, List<Long> ids, Instant changedAt) {
        String table = type.table();
        query("delete from infrastructure_kubernetes_event e where e.resource_type = :type and e.resource_id"
                + " in (select id from " + table + " where id in (:ids) and status = 'NORMAL')")
                .setParameter("type", type.name()).unwrap(org.hibernate.query.NativeQuery.class)
                .setParameterList("ids", ids).executeUpdate();

        String reason = switch (type) {
            case CLUSTER -> "ClusterDegraded";
            case NODE -> "NodeNotReady";
            case NAMESPACE -> "NamespaceDegraded";
            case POD -> "BackOff";
        };
        String message = switch (type) {
            case CLUSTER -> "One or more cluster health checks are degraded.";
            case NODE -> "Kubelet or node pressure condition is degraded.";
            case NAMESPACE -> "One or more namespace workloads are not ready.";
            case POD -> "Pod scheduling, readiness, or container restart condition is degraded.";
        };
        String insert = "insert into infrastructure_kubernetes_event"
                + "(resource_type,resource_id,event_type,reason,message,first_seen_at,last_seen_at,occurrence_count)"
                + " select :type,r.id,case when r.status='ERROR' then 'WARNING' else 'NORMAL' end,:reason,:message,"
                + ":changedAt,:changedAt,1 from " + table + " r where r.id in (:ids) and r.status <> 'NORMAL'"
                + " on conflict(resource_type,resource_id) do update set event_type=excluded.event_type,"
                + " reason=excluded.reason,message=excluded.message,last_seen_at=excluded.last_seen_at,"
                + " occurrence_count=least(infrastructure_kubernetes_event.occurrence_count::bigint+1,2147483647)::integer";
        query(insert).unwrap(org.hibernate.query.NativeQuery.class).setParameter("type", type.name())
                .setParameter("reason", reason).setParameter("message", message)
                .setParameter("changedAt", Timestamp.from(changedAt)).setParameterList("ids", ids).executeUpdate();
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
