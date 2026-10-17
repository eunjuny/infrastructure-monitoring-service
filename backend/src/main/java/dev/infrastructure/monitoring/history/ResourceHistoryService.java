package dev.infrastructure.monitoring.history;

import dev.infrastructure.monitoring.resource.ResourceRequestException;
import dev.infrastructure.monitoring.resource.ResourceService;
import dev.infrastructure.monitoring.resource.ResourceType;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Service
public class ResourceHistoryService {
    private static final int MAX_PAGE_SIZE = 100;

    private final ResourceHistoryRepository history;
    private final ResourceService resources;
    private final int retentionHours;
    private final int maximumEvents;

    public ResourceHistoryService(ResourceHistoryRepository history, ResourceService resources,
            @Value("${monitoring.history.retention-hours:24}") int retentionHours,
            @Value("${monitoring.history.max-events:20000}") int maximumEvents) {
        this.history = history;
        this.resources = resources;
        this.retentionHours = retentionHours;
        this.maximumEvents = maximumEvents;
    }

    public ResourceHistory.Page list(String type, long id, int limit, Long beforeId) {
        validatePagination(limit, beforeId);
        ResourceType resourceType = ResourceType.parse(type);

        // History rows do not carry Cluster scope; check resource access before reading them.
        resources.detail(resourceType, id);

        var items = history.list(resourceType, id, beforeId, limit + 1);
        boolean hasMore = items.size() > limit;
        var selected = items.subList(0, Math.min(limit, items.size()));
        Long nextBeforeId = hasMore ? selected.getLast().id() : null;
        return new ResourceHistory.Page(selected, nextBeforeId, hasMore, retentionHours, maximumEvents);
    }

    private static void validatePagination(int limit, Long beforeId) {
        if (limit < 1 || limit > MAX_PAGE_SIZE || (beforeId != null && beforeId <= 0)) {
            throw ResourceRequestException.invalid("events", "이력 limit은 1–100, beforeId는 양수여야 합니다.");
        }
    }
}
