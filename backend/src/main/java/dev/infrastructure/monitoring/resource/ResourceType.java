package dev.infrastructure.monitoring.resource;

import java.util.Locale;

public enum ResourceType {
    CLUSTER("infrastructure_cluster", null, 0),
    NODE("infrastructure_node", "cluster_id", 1),
    NAMESPACE("infrastructure_namespace", "node_id", 2),
    POD("infrastructure_pod", "namespace_id", 3);

    private final String table;
    private final String parentColumn;
    private final int depth;

    ResourceType(String table, String parentColumn, int depth) {
        this.table = table;
        this.parentColumn = parentColumn;
        this.depth = depth;
    }

    public String table() { return table; }
    public String parentColumn() { return parentColumn; }
    public int depth() { return depth; }

    public ResourceType child() {
        return switch (this) {
            case CLUSTER -> NODE;
            case NODE -> NAMESPACE;
            case NAMESPACE -> POD;
            case POD -> null;
        };
    }

    public ResourceType parent() {
        return switch (this) {
            case CLUSTER -> null;
            case NODE -> CLUSTER;
            case NAMESPACE -> NODE;
            case POD -> NAMESPACE;
        };
    }

    public static ResourceType parse(String value) {
        if (value == null || value.isBlank()) {
            throw ResourceRequestException.invalid("type", "리소스 유형이 필요합니다.");
        }
        try {
            return valueOf(value.strip().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException exception) {
            throw ResourceRequestException.invalid("type", "유형은 CLUSTER, NODE, NAMESPACE, POD 중 하나여야 합니다.");
        }
    }
}
