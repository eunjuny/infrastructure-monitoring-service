package dev.infrastructure.monitoring.resource;

import java.time.Instant;
import java.util.List;
import java.util.Map;

public final class ResourceResponses {
    private ResourceResponses() { }

    public record Reference(ResourceType type, long id, String name) { }
    public record Item(ResourceType type, long id, String name, ResourceStatus status,
                       Instant updatedAt, Reference parent, boolean hasChildren) { }
    public record Detail(Item resource, Map<String, Object> attributes, List<Reference> path) { }
    public record SearchResult(Item resource, List<Reference> path) { }
    public record Path(List<Reference> items) { }
    public record Page<T>(List<T> items, String nextCursor, boolean hasMore) { }
}
