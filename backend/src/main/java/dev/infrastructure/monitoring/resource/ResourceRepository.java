package dev.infrastructure.monitoring.resource;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import javax.sql.DataSource;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

import static dev.infrastructure.monitoring.resource.ResourceResponses.*;

@Repository
public class ResourceRepository {
    public record Row(Item resource, Map<String, Object> attributes, List<Reference> path) { }

    private final NamedParameterJdbcTemplate jdbc;

    public ResourceRepository(DataSource dataSource,
                              @Value("${monitoring.query-timeout-seconds:3}") int timeout) {
        if (timeout < 1) throw new IllegalArgumentException("Query timeout must be positive");
        jdbc = new NamedParameterJdbcTemplate(dataSource);
        jdbc.getJdbcTemplate().setQueryTimeout(timeout);
    }

    public boolean exists(ResourceType type, long id) {
        return Boolean.TRUE.equals(jdbc.queryForObject(
                "select exists(select 1 from " + type.table() + " where id = :id)",
                Map.of("id", id), Boolean.class));
    }

    public Optional<Row> find(ResourceType type, long id) {
        return jdbc.query(select(type) + " where r.id = :id", Map.of("id", id),
                this::map).stream().findFirst();
    }

    public List<Row> list(ResourceQuery query, ResourceCursorCodec.Position position) {
        return jdbc.query(listSql(query, position), parameters(query, position), this::map);
    }

    public List<Row> search(ResourceQuery query, ResourceCursorCodec.Position position) {
        return jdbc.query(searchSql(query, position), parameters(query, position), this::map);
    }

    String listSql(ResourceQuery query, ResourceCursorCodec.Position position) {
        return select(query.type()) + where(query, query.type(), position)
                + " order by r.id limit :fetchLimit";
    }

    String searchSql(ResourceQuery query, ResourceCursorCodec.Position position) {
        // Branch limits bound the outer merge to at most 4 * (limit + 1) rows.
        String branches = Arrays.stream(ResourceType.values())
                .filter(type -> query.type() == null || query.type() == type)
                .map(type -> "(" + select(type) + where(query, type, position)
                        + " order by r.id limit :fetchLimit)")
                .collect(Collectors.joining(" union all "));
        return "select * from (" + branches + ") matches"
                + " order by depth, resource_id limit :fetchLimit";
    }

    MapSqlParameterSource parameters(ResourceQuery query, ResourceCursorCodec.Position position) {
        var params = new MapSqlParameterSource("fetchLimit", query.limit() + 1)
                .addValue("pattern", query.likePattern());
        if (query.status() != null) params.addValue("status", query.status().name());
        if (query.parentId() != null) params.addValue("parentId", query.parentId());
        if (position != null) {
            params.addValue("afterDepth", position.depth()).addValue("afterId", position.id());
        }
        return params;
    }

    private String where(ResourceQuery query, ResourceType type, ResourceCursorCodec.Position position) {
        var terms = new ArrayList<String>();
        if (!query.query().isEmpty()) terms.add("r.name ilike :pattern escape '!'");
        if (query.status() != null) terms.add("r.status = :status");
        if (query.parentId() != null) terms.add("r." + type.parentColumn() + " = :parentId");
        if (position != null) terms.add("(" + type.depth() + " > :afterDepth or ("
                + type.depth() + " = :afterDepth and r.id > :afterId))");
        return terms.isEmpty() ? "" : " where " + String.join(" and ", terms);
    }

    private String select(ResourceType type) {
        String ancestors = switch (type) {
            case CLUSTER -> "r.id cluster_id, r.name cluster_name, "
                    + "null::bigint node_id, null::text node_name, "
                    + "null::bigint namespace_id, null::text namespace_name";
            case NODE -> "c.id cluster_id, c.name cluster_name, "
                    + "r.id node_id, r.name node_name, "
                    + "null::bigint namespace_id, null::text namespace_name";
            case NAMESPACE -> "c.id cluster_id, c.name cluster_name, "
                    + "n.id node_id, n.name node_name, "
                    + "r.id namespace_id, r.name namespace_name";
            case POD -> "c.id cluster_id, c.name cluster_name, "
                    + "n.id node_id, n.name node_name, "
                    + "ns.id namespace_id, ns.name namespace_name";
        };
        String joins = switch (type) {
            case CLUSTER -> "";
            case NODE -> " join infrastructure_cluster c on c.id = r.cluster_id";
            case NAMESPACE -> " join infrastructure_node n on n.id = r.node_id"
                    + " join infrastructure_cluster c on c.id = n.cluster_id";
            case POD -> " join infrastructure_namespace ns on ns.id = r.namespace_id"
                    + " join infrastructure_node n on n.id = ns.node_id"
                    + " join infrastructure_cluster c on c.id = n.cluster_id";
        };
        ResourceType child = type.child();
        String hasChildren = child == null ? "false" : "exists(select 1 from "
                + child.table() + " child where child." + child.parentColumn() + " = r.id)";
        String metrics = type == ResourceType.NODE
                ? "r.cpu_usage cpu_usage, r.memory_usage memory_usage"
                : "null::numeric cpu_usage, null::numeric memory_usage";
        String restarts = type == ResourceType.POD ? "r.restart_count" : "null::integer";
        // SQL identifiers are exclusively enum constants. Client values are bound parameters.
        return "select r.id resource_id, r.name resource_name, r.status resource_status,"
                + " r.updated_at resource_updated_at, '" + type.name() + "' resource_type, "
                + type.depth() + " depth, " + ancestors + ", " + metrics + ", "
                + restarts + " restart_count, " + hasChildren + " has_children"
                + " from " + type.table() + " r" + joins;
    }

    private Row map(ResultSet rs, int rowNumber) throws SQLException {
        ResourceType type = ResourceType.valueOf(rs.getString("resource_type"));
        var path = new ArrayList<Reference>();
        addReference(path, rs, ResourceType.CLUSTER, "cluster");
        addReference(path, rs, ResourceType.NODE, "node");
        addReference(path, rs, ResourceType.NAMESPACE, "namespace");
        if (type == ResourceType.POD) path.add(new Reference(type,
                rs.getLong("resource_id"), rs.getString("resource_name")));
        Reference parent = path.size() < 2 ? null : path.get(path.size() - 2);
        var item = new Item(type, rs.getLong("resource_id"), rs.getString("resource_name"),
                ResourceStatus.valueOf(rs.getString("resource_status")),
                rs.getTimestamp("resource_updated_at").toInstant(), parent,
                rs.getBoolean("has_children"));
        Map<String, Object> attributes = switch (type) {
            case NODE -> Map.of("cpuUsage", rs.getBigDecimal("cpu_usage"),
                    "memoryUsage", rs.getBigDecimal("memory_usage"));
            case POD -> Map.of("restartCount", rs.getInt("restart_count"));
            default -> Map.of();
        };
        return new Row(item, attributes, List.copyOf(path));
    }

    private void addReference(List<Reference> path, ResultSet rs,
                              ResourceType type, String prefix) throws SQLException {
        Long id = rs.getObject(prefix + "_id", Long.class);
        if (id != null) path.add(new Reference(type, id, rs.getString(prefix + "_name")));
    }
}
