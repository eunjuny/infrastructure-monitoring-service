package dev.infrastructure.monitoring.persistence;

import jakarta.persistence.*;
import java.math.BigDecimal;

@Entity
@Table(name = "infrastructure_node")
public class NodeResource extends BaseResource {
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "cluster_id", nullable = false) private ClusterResource cluster;
    @Column(name = "cpu_usage", nullable = false, precision = 5, scale = 2) private BigDecimal cpuUsage;
    @Column(name = "memory_usage", nullable = false, precision = 5, scale = 2) private BigDecimal memoryUsage;

    protected NodeResource() { }
}
