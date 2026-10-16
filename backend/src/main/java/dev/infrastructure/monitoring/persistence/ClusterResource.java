package dev.infrastructure.monitoring.persistence;

import jakarta.persistence.*;

@Entity
@Table(name = "infrastructure_cluster")
public class ClusterResource extends BaseResource {
    protected ClusterResource() { }
}
