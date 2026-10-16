package dev.infrastructure.monitoring.history;

import dev.infrastructure.monitoring.resource.ResourceType;
import jakarta.persistence.EntityManager;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
import java.util.List;

@Repository
@Transactional(readOnly = true)
public class ResourceHistoryRepository {
    private final EntityManager entityManager;
    private final int timeoutMillis;

    public ResourceHistoryRepository(EntityManager entityManager, @Value("${monitoring.query-timeout-seconds:3}") int timeout) {
        if (timeout < 1) throw new IllegalArgumentException("Query timeout must be positive");
        this.entityManager = entityManager;
        timeoutMillis = Math.multiplyExact(timeout, 1000);
    }

    public List<ResourceHistory.Event> list(ResourceType type, long id, Long before, int limit) {
        var query = entityManager.createQuery("select new dev.infrastructure.monitoring.history.ResourceHistory$Event("
                + "e.id,e.resourceType,e.resourceId,e.occurredAt,e.previousStatus,e.nextStatus,e.source,e.beforeAttributes,e.afterAttributes)"
                + " from ResourceChangeEvent e where e.resourceType=:type and e.resourceId=:id"
                + (before == null ? "" : " and e.id < :before") + " order by e.id desc", ResourceHistory.Event.class)
                .setParameter("type", type.name()).setParameter("id", id).setMaxResults(limit)
                .setHint("jakarta.persistence.query.timeout", timeoutMillis).setHint("org.hibernate.readOnly", true);
        if (before != null) query.setParameter("before", before);
        return query.getResultList();
    }

    @Transactional
    public int cleanup(int hours, int maximum, int batch) {
        entityManager.flush();
        int deleted = entityManager.createNativeQuery("delete from resource_change_event where id in (select id from resource_change_event"
                + " where occurred_at < now() - make_interval(hours => :hours) or id < coalesce((select id from resource_change_event order by id desc offset :maximum limit 1),0)"
                + " order by id limit :batch)")
                .setParameter("hours", hours).setParameter("maximum", maximum - 1).setParameter("batch", batch)
                .setHint("jakarta.persistence.query.timeout", timeoutMillis).executeUpdate();
        entityManager.clear();
        return deleted;
    }
}
