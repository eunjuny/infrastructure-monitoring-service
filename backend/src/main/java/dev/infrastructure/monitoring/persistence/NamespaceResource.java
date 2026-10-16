package dev.infrastructure.monitoring.persistence;

import jakarta.persistence.*;

@Entity
@Table(name = "infrastructure_namespace")
public class NamespaceResource extends BaseResource {
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "node_id", nullable = false) private NodeResource node;

    protected NamespaceResource() { }
}
