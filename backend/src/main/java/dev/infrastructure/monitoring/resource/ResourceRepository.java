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

    /** Detail-only scalar queries; every aggregate retains the server's Cluster scope. */
    public Map<String, Object> diagnostics(Item resource) {
        ResourceType type = resource.type();
        long id = resource.id();
        return switch (type) {
            case CLUSTER -> {
                var nodes = diagnosticRow("select count(r.id), coalesce(sum(case when r.status <> 'ERROR' then 1 else 0 end),0)"
                        + " from NodeResource r where r.cluster.id = :id" + accessWhere(ResourceType.NODE), id);
                var pods = podCounts("r.namespace.node.cluster.id = :id", id);
                yield Map.of("nodeCount", nodes[0], "readyNodeCount", nodes[1],
                        "podCount", pods[0], "problemPodCount", pods[2]);
            }
            case NAMESPACE -> {
                var pods = podCounts("r.namespace.id = :id", id);
                yield Map.of("podCount", pods[0], "errorPodCount", pods[1], "notReadyPodCount", pods[2]);
            }
            case NODE -> {
                var node = diagnosticRow("select r.cpuCapacityCores, r.memoryCapacityMiB, r.cpuAllocatableCores,"
                        + " r.cpuRequestedCores, r.memoryAllocatableMiB, r.memoryRequestedMiB, r.cpuUsage, r.memoryUsage"
                        + " from NodeResource r"
                        + " where r.id = :id" + accessWhere(type), id);
                var values = new LinkedHashMap<String, Object>(
                        MockDiagnostics.node(resource.status(), id, (Number) node[0], (Number) node[1]));
                values.put("cpuAllocatableCores", node[2]);
                values.put("cpuRequestedCores", node[3]);
                values.put("cpuUsageCores", ((Number) node[0]).doubleValue()
                        * ((Number) node[6]).doubleValue() / 100.0);
                values.put("memoryAllocatableMiB", node[4]);
                values.put("memoryRequestedMiB", node[5]);
                values.put("memoryUsageMiB", ((Number) node[1]).doubleValue()
                        * ((Number) node[7]).doubleValue() / 100.0);
                values.put("gpuDevices", gpuDevices(id));
                yield Collections.unmodifiableMap(values);
            }
            case POD -> {
                var pod = diagnosticRow("select r.containerCount, r.image, r.workloadKind, r.workloadName"
                        + " from PodResource r where r.id = :id" + accessWhere(type), id);
                var values = new LinkedHashMap<String, Object>(MockDiagnostics.pod(resource.status(), id,
                        ((Number) pod[0]).intValue(), (String) pod[1], (String) pod[2], (String) pod[3]));
                values.put("containers", containers(id));
                yield Collections.unmodifiableMap(values);
            }
        };
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> gpuDevices(long nodeId) {
        var rows = entityManager.createNativeQuery("select device_name, model, memory_mib, utilization_percent,"
                + " temperature_celsius, allocated_pods, updated_at from infrastructure_node_gpu"
                + " where node_id = :id order by id limit 8")
                .setParameter("id", nodeId).setHint("jakarta.persistence.query.timeout", timeoutMillis).getResultList();
        return ((List<Object[]>) rows).stream().map(row -> Map.<String, Object>of(
                "name", row[0], "model", row[1], "memoryMiB", row[2], "utilizationPercent", row[3],
                "temperatureCelsius", row[4], "allocatedPods", row[5], "updatedAt", row[6])).toList();
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> containers(long podId) {
        var rows = entityManager.createNativeQuery("select name, image, state, ready, restart_count,"
                + " cpu_request_millicores, cpu_usage_millicores, memory_request_mib, memory_usage_mib"
                + " from infrastructure_pod_container where pod_id = :id order by id limit 4")
                .setParameter("id", podId).setHint("jakarta.persistence.query.timeout", timeoutMillis).getResultList();
        return ((List<Object[]>) rows).stream().map(row -> Map.<String, Object>of(
                "name", row[0], "image", row[1], "state", row[2], "ready", row[3], "restartCount", row[4],
                "cpuRequestMillicores", row[5], "cpuUsageMillicores", row[6],
                "memoryRequestMiB", row[7], "memoryUsageMiB", row[8])).toList();
    }

    public List<Map<String, Object>> kubernetesEvents(ResourceType type, long id, int limit) {
        var resource = find(type, id).orElseThrow(ResourceRequestException::notFound);
        var permissions = access.scope();
        String joins = switch (type) {
            case CLUSTER -> "";
            case NODE -> "";
            case NAMESPACE -> " join infrastructure_node n on n.id = r.node_id";
            case POD -> " join infrastructure_namespace ns on ns.id = r.namespace_id"
                    + " join infrastructure_node n on n.id = ns.node_id";
        };
        String scope = !permissions.all() && permissions.clusters().isEmpty() ? " and 1 = 0"
                : !permissions.all() ? " and " + switch (type) {
                    case CLUSTER -> "r.id";
                    case NODE -> "r.cluster_id";
                    case NAMESPACE -> "n.cluster_id";
                    case POD -> "n.cluster_id";
                } + " in (:accessibleClusters)" : "";
        String sql = "select e.id, e.event_type, e.reason, e.message, e.first_seen_at, e.last_seen_at, e.occurrence_count"
                + " from infrastructure_kubernetes_event e join " + type.table() + " r on r.id = e.resource_id" + joins
                + " where e.resource_type = :type and e.resource_id = :id" + scope
                + " order by e.last_seen_at desc, e.id desc limit :limit";
        var query = entityManager.createNativeQuery(sql).setParameter("type", type.name()).setParameter("id", id)
                .setParameter("limit", limit).setHint("jakarta.persistence.query.timeout", timeoutMillis);
        if (!permissions.all() && !permissions.clusters().isEmpty()) {
            query.setParameter("accessibleClusters", permissions.clusters());
        }
        @SuppressWarnings("unchecked") List<Object[]> rows = query.getResultList();
        return rows.stream().map(row -> Map.<String, Object>of("id", row[0], "type", row[1], "reason", row[2],
                "message", row[3], "firstSeenAt", row[4], "lastSeenAt", row[5], "occurrenceCount", row[6])).toList();
    }

    private Object[] podCounts(String scope, long id) {
        return diagnosticRow("select count(r.id), coalesce(sum(case when r.status = 'ERROR' then 1 else 0 end),0),"
                + " coalesce(sum(case when r.status <> 'NORMAL' then 1 else 0 end),0) from PodResource r where "
                + scope + accessWhere(ResourceType.POD), id);
    }

    private Object[] diagnosticRow(String hql, long id) {
        return access.bind(query(hql, Object[].class)).setParameter("id", id).getSingleResult();
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
