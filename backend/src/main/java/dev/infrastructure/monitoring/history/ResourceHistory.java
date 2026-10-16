package dev.infrastructure.monitoring.history;

import java.time.Instant;
import java.util.List;
import com.fasterxml.jackson.databind.JsonNode;

public final class ResourceHistory {
    private ResourceHistory() { }
    public record Event(long id, String resourceType, long resourceId, Instant occurredAt, String previousStatus,
                        String nextStatus, String source, JsonNode beforeAttributes, JsonNode afterAttributes) { }
    public record Page(List<Event> items, Long nextBeforeId, boolean hasMore, int retentionHours, int maximumEvents) { }
}
