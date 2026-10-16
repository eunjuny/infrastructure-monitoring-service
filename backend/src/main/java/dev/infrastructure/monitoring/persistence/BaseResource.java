package dev.infrastructure.monitoring.persistence;

import dev.infrastructure.monitoring.resource.ResourceStatus;
import jakarta.persistence.*;
import java.time.Instant;

@MappedSuperclass
public abstract class BaseResource {
    @Id protected Long id;
    @Column(nullable = false, length = 160) protected String name;
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16) protected ResourceStatus status;
    @Column(name = "updated_at", nullable = false) protected Instant updatedAt;

    protected BaseResource() { }
}
