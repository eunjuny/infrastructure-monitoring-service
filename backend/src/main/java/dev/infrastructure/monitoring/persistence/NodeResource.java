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
    @Column(name = "cpu_capacity_cores", nullable = false) private int cpuCapacityCores;
    @Column(name = "memory_capacity_mib", nullable = false) private long memoryCapacityMiB;
    @Column(name = "cpu_allocatable_cores", nullable = false, precision = 8, scale = 2) private BigDecimal cpuAllocatableCores;
    @Column(name = "cpu_requested_cores", nullable = false, precision = 8, scale = 2) private BigDecimal cpuRequestedCores;
    @Column(name = "memory_allocatable_mib", nullable = false) private long memoryAllocatableMiB;
    @Column(name = "memory_requested_mib", nullable = false) private long memoryRequestedMiB;

    protected NodeResource() { }
}
