package dev.infrastructure.monitoring.persistence;

import jakarta.persistence.*;

@Entity
@Table(name = "infrastructure_pod")
public class PodResource extends BaseResource {
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "namespace_id", nullable = false) private NamespaceResource namespace;
    @Column(name = "restart_count", nullable = false) private int restartCount;

    protected PodResource() { }
}
