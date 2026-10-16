package dev.infrastructure.monitoring.persistence;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import java.time.Instant;

@Entity
@Table(name = "resource_change_event")
public class ResourceChangeEvent {
    @Id private Long id;
    @Column(name = "resource_type", nullable = false, length = 16) private String resourceType;
    @Column(name = "resource_id", nullable = false) private long resourceId;
    @Column(name = "occurred_at", nullable = false) private Instant occurredAt;
    @Column(name = "previous_status", nullable = false, length = 16) private String previousStatus;
    @Column(name = "next_status", nullable = false, length = 16) private String nextStatus;
    @Column(nullable = false, length = 32) private String source;
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "before_attributes", nullable = false, columnDefinition = "jsonb") private JsonNode beforeAttributes;
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "after_attributes", nullable = false, columnDefinition = "jsonb") private JsonNode afterAttributes;

    protected ResourceChangeEvent() { }
}
