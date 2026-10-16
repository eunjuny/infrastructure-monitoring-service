package dev.infrastructure.monitoring.resource;

import jakarta.persistence.EntityManager;
import jakarta.persistence.TypedQuery;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.annotation.Autowired;
import dev.infrastructure.monitoring.security.ResourceAccess;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
import java.util.*;
import static dev.infrastructure.monitoring.resource.ResourceResponses.*;

@Repository
@Transactional(readOnly = true)
public class ResourceRepository {
    public record Row(Item resource, Map<String, Object> attributes, List<Reference> path) { }
    private final EntityManager entityManager;
    private final int timeoutMillis;
    private final ResourceAccess access;

    public ResourceRepository(EntityManager entityManager, int timeout) {
        this(entityManager, timeout, new ResourceAccess(false));
    }

    @Autowired
    public ResourceRepository(EntityManager entityManager, @Value("${monitoring.query-timeout-seconds:3}") int timeout,
                              ResourceAccess access) {
        if (timeout < 1) throw new IllegalArgumentException("Query timeout must be positive");
        this.entityManager = entityManager;
        this.timeoutMillis = Math.multiplyExact(timeout, 1000);
        this.access = access;
    }

    public static String entityName(ResourceType type) {
        return switch (type) {
            case CLUSTER -> "ClusterResource";
            case NODE -> "NodeResource";
            case NAMESPACE -> "NamespaceResource";
            case POD -> "PodResource";
        };
    }

    public boolean exists(ResourceType type, long id) {
        return !access.bind(query("select r.id from " + entityName(type) + " r where r.id = :id"
                + accessWhere(type), Long.class)).setParameter("id", id).setMaxResults(1).getResultList().isEmpty();
    }

    public Optional<Row> find(ResourceType type, long id) {
        return access.bind(query(select(type) + " where r.id = :id" + accessWhere(type), ResourceProjection.class))
                .setParameter("id", id).setMaxResults(1).getResultList().stream().findFirst().map(value -> value.row(type));
    }

    public List<Row> list(ResourceQuery request, ResourceCursorCodec.Position position) {
        return fetch(request, request.type(), position, request.limit() + 1);
    }

    public List<Row> search(ResourceQuery request, ResourceCursorCodec.Position position) {
        // At most four bounded queries. Stop once limit+1 is filled; never load all matching rows.
        var rows = new ArrayList<Row>();
        int maximum = request.limit() + 1;
        for (ResourceType type : ResourceType.values()) {
            if (request.type() != null && request.type() != type) continue;
            if (request.namespaceId() != null && type.depth() < ResourceType.NAMESPACE.depth()) continue;
            if (position != null && type.depth() < position.depth()) continue;
            rows.addAll(fetch(request, type, position, maximum - rows.size()));
            if (rows.size() == maximum) break;
        }
        return List.copyOf(rows);
    }

    private List<Row> fetch(ResourceQuery request, ResourceType type, ResourceCursorCodec.Position position, int maximum) {
        var query = query(listHql(request, type, position), ResourceProjection.class).setMaxResults(maximum);
        parameters(request, type, position).forEach(query::setParameter);
        return query.getResultList().stream().map(value -> value.row(type)).toList();
    }

    private <T> TypedQuery<T> query(String hql, Class<T> resultType) {
        return entityManager.createQuery(hql, resultType).setHint("jakarta.persistence.query.timeout", timeoutMillis)
                .setHint("org.hibernate.readOnly", true);
    }

    String listHql(ResourceQuery request, ResourceType type, ResourceCursorCodec.Position position) {
        var terms = new ArrayList<String>();
        String authorization = access.predicate(type);
        if (!authorization.isEmpty()) terms.add(authorization);
        // Hibernate HQL ilike preserves PostgreSQL's existing pg_trgm index expression.
        if (!request.query().isEmpty()) terms.add("r.name ilike :pattern escape '!'");
        if (request.status() != null) terms.add("r.status = :status");
        if (request.parentId() != null) terms.add("r." + parentProperty(type) + ".id = :parentId");
        if (request.clusterId() != null) terms.add(type == ResourceType.CLUSTER ? "r.id = :clusterId" : "c.id = :clusterId");
        if (request.namespaceId() != null) terms.add(type == ResourceType.POD ? "ns.id = :namespaceId" : "r.id = :namespaceId");
        if (position != null && type.depth() == position.depth()) terms.add("r.id > :afterId");
        return select(type) + (terms.isEmpty() ? "" : " where " + String.join(" and ", terms)) + " order by r.id";
    }

    Map<String, Object> parameters(ResourceQuery request, ResourceType type, ResourceCursorCodec.Position position) {
        var parameters = new LinkedHashMap<String, Object>();
        var permissions = access.scope();
        if (!permissions.all() && !permissions.clusters().isEmpty()) parameters.put("accessibleClusters", permissions.clusters());
        if (!request.query().isEmpty()) parameters.put("pattern", request.likePattern());
        if (request.status() != null) parameters.put("status", request.status());
        if (request.parentId() != null) parameters.put("parentId", request.parentId());
        if (request.clusterId() != null) parameters.put("clusterId", request.clusterId());
        if (request.namespaceId() != null) parameters.put("namespaceId", request.namespaceId());
        if (position != null && type.depth() == position.depth()) parameters.put("afterId", position.id());
        return parameters;
    }

    private String accessWhere(ResourceType type) {
        String predicate = access.predicate(type);
        return predicate.isEmpty() ? "" : " and " + predicate;
    }

    private static String parentProperty(ResourceType type) {
        return switch (type) { case NODE -> "cluster"; case NAMESPACE -> "node"; case POD -> "namespace"; case CLUSTER -> throw new IllegalArgumentException("Cluster has no parent"); };
    }

    private String select(ResourceType type) {
        String ancestors = switch (type) {
            case CLUSTER -> "r.id, r.name, null, null, null, null";
            case NODE -> "c.id, c.name, r.id, r.name, null, null";
            case NAMESPACE -> "c.id, c.name, n.id, n.name, r.id, r.name";
            case POD -> "c.id, c.name, n.id, n.name, ns.id, ns.name";
        };
        String joins = switch (type) {
            case CLUSTER -> "";
            case NODE -> " join r.cluster c";
            case NAMESPACE -> " join r.node n join n.cluster c";
            case POD -> " join r.namespace ns join ns.node n join n.cluster c";
        };
        ResourceType child = type.child();
        String children = child == null ? "false" : "case when exists(select child.id from "
                + entityName(child) + " child where child." + parentProperty(child) + " = r) then true else false end";
        String metrics = type == ResourceType.NODE ? "r.cpuUsage, r.memoryUsage" : "null, null";
        String restarts = type == ResourceType.POD ? "r.restartCount" : "null";
        return "select new dev.infrastructure.monitoring.resource.ResourceProjection(r.id, r.name, r.status, r.updatedAt, "
                + ancestors + ", " + metrics + ", " + restarts + ", " + children + ") from " + entityName(type) + " r" + joins;
    }
}
