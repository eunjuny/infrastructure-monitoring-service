package dev.infrastructure.monitoring.resource;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import static dev.infrastructure.monitoring.resource.ResourceResponses.*;

/** Scalar constructor projection: no managed resource/ancestor graph is materialized. */
public record ResourceProjection(Long id, String name, ResourceStatus status, Instant updatedAt,
                                 Long clusterId, String clusterName, Long nodeId, String nodeName,
                                 Long namespaceId, String namespaceName, BigDecimal cpuUsage,
                                 BigDecimal memoryUsage, Integer restartCount, Boolean hasChildren) {
    ResourceRepository.Row row(ResourceType type) {
        var path = new ArrayList<Reference>();
        add(path, ResourceType.CLUSTER, clusterId, clusterName);
        add(path, ResourceType.NODE, nodeId, nodeName);
        add(path, ResourceType.NAMESPACE, namespaceId, namespaceName);
        if (type == ResourceType.POD) add(path, type, id, name);
        Reference parent = path.size() < 2 ? null : path.get(path.size() - 2);
        var item = new Item(type, id, name, status, updatedAt, parent, hasChildren);
        Map<String, Object> attributes = switch (type) {
            case NODE -> Map.of("cpuUsage", cpuUsage, "memoryUsage", memoryUsage);
            case POD -> Map.of("restartCount", restartCount);
            default -> Map.of();
        };
        return new ResourceRepository.Row(item, attributes, List.copyOf(path));
    }

    private static void add(List<Reference> path, ResourceType type, Long id, String name) {
        if (id != null) path.add(new Reference(type, id, name));
    }
}
