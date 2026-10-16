package dev.infrastructure.monitoring.resource;

import jakarta.persistence.EntityManager;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.annotation.Autowired;
import dev.infrastructure.monitoring.security.ResourceAccess;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
@Transactional(readOnly = true)
public class ResourceCountRepository {
    private final EntityManager entityManager;
    private final int timeoutMillis;
    private final ResourceAccess access;

    public ResourceCountRepository(EntityManager entityManager, int timeout) {
        this(entityManager, timeout, new ResourceAccess(false));
    }

    @Autowired
    public ResourceCountRepository(EntityManager entityManager, @Value("${monitoring.query-timeout-seconds:3}") int timeout,
                                   ResourceAccess access) {
        if (timeout < 1) throw new IllegalArgumentException("Query timeout must be positive");
        this.entityManager = entityManager;
        timeoutMillis = Math.multiplyExact(timeout, 1000);
        this.access = access;
    }

    public ResourceSummaryController.ResourceSummary summary() {
        return new ResourceSummaryController.ResourceSummary(count(ResourceType.CLUSTER), count(ResourceType.NODE),
                count(ResourceType.NAMESPACE), count(ResourceType.POD));
    }

    private long count(ResourceType type) {
        String predicate = access.predicate(type);
        return access.bind(entityManager.createQuery("select count(r) from " + ResourceRepository.entityName(type)
                + " r" + (predicate.isEmpty() ? "" : " where " + predicate), Long.class))
                .setHint("jakarta.persistence.query.timeout", timeoutMillis).getSingleResult();
    }
}
