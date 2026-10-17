package dev.infrastructure.monitoring.persistence;

import jakarta.persistence.*;

@Entity
@Table(name = "infrastructure_pod")
public class PodResource extends BaseResource {
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "namespace_id", nullable = false) private NamespaceResource namespace;
    @Column(name = "restart_count", nullable = false) private int restartCount;
    @Column(name = "container_count", nullable = false) private int containerCount;
    @Column(nullable = false, length = 240) private String image;
    @Column(name = "workload_kind", nullable = false, length = 32) private String workloadKind;
    @Column(name = "workload_name", nullable = false, length = 160) private String workloadName;

    protected PodResource() { }
}
