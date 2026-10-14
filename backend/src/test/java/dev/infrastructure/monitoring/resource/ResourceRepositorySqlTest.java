package dev.infrastructure.monitoring.resource;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.namedparam.NamedParameterUtils;

import static org.junit.jupiter.api.Assertions.*;

/** SQL construction/binding checks only, not execution-plan or DB-result tests. */
class ResourceRepositorySqlTest {
    private final ResourceRepository repository = new ResourceServiceTest.StubRepository();

    @Test void usesParentAndIdKeysetInsteadOfOffset() {
        var q = ResourceQuery.list("POD", 1L, "", null, 100, null);
        var p = new ResourceCursorCodec.Position(3, 500);
        String sql = repository.listSql(q, p);
        assertTrue(sql.contains("r.namespace_id = :parentId"));
        assertTrue(sql.contains("r.id > :afterId"));
        assertTrue(sql.endsWith("order by r.id limit :fetchLimit"));
        assertFalse(sql.toLowerCase().contains("offset"));
        assertEquals(101, repository.parameters(q, p).getValue("fetchLimit"));
        assertBinding(sql, q, p);
    }

    @Test void keepsClientTextOutOfSqlAndEscapesWildcards() {
        var q = ResourceQuery.list("POD", 1L, "' OR 1=1 !%_", "ERROR", 50, null);
        String sql = repository.listSql(q, null);
        assertFalse(sql.contains(q.query()));
        assertTrue(sql.contains("r.name ilike :pattern escape '!'"));
        assertEquals(q.likePattern(), repository.parameters(q, null).getValue("pattern"));
        assertEquals("ERROR", repository.parameters(q, null).getValue("status"));
        assertBinding(sql, q, null);
    }

    @Test void boundsAllFourSearchBranchesAndOuterMerge() {
        var q = ResourceQuery.search(null, "pod", null, 50, null);
        String sql = repository.searchSql(q, null);
        assertEquals(4, sql.split(" union all ", -1).length);
        assertEquals(5, sql.split("limit :fetchLimit", -1).length - 1);
        assertTrue(sql.endsWith("order by depth, resource_id limit :fetchLimit"));
        assertBinding(sql, q, null);
    }

    @Test void typedSearchDoesNotQueryOtherResourceTablesAsBranches() {
        var q = ResourceQuery.search("NODE", "node", "NORMAL", 10, null);
        String sql = repository.searchSql(q, null);
        assertFalse(sql.contains(" union all "));
        assertTrue(sql.contains("from infrastructure_node r"));
        assertFalse(sql.contains("from infrastructure_pod r"));
        assertBinding(sql, q, null);
    }

    @Test void globalCursorAppliesDepthAndIdBounds() {
        var q = ResourceQuery.search(null, "01", "ERROR", 50, null);
        var p = new ResourceCursorCodec.Position(1, 9);
        String sql = repository.searchSql(q, p);
        assertTrue(sql.contains("1 = :afterDepth and r.id > :afterId"));
        assertTrue(sql.contains("3 > :afterDepth"));
        assertBinding(sql, q, p);
    }

    @Test void everyTypeUsesValidNamedBindingsWithAndWithoutFilters() {
        for (var type : ResourceType.values()) {
            Long parent = type.parent() == null ? null : 1L;
            var filtered = ResourceQuery.list(type.name(), parent, "name", "WARNING", 20, null);
            var plain = ResourceQuery.list(type.name(), null, "", null, 20, null);
            assertBinding(repository.listSql(filtered, null), filtered, null);
            assertBinding(repository.listSql(plain, null), plain, null);
        }
    }

    private void assertBinding(String sql, ResourceQuery query, ResourceCursorCodec.Position position) {
        var parsed = NamedParameterUtils.parseSqlStatement(sql);
        var params = repository.parameters(query, position);
        Object[] bound = NamedParameterUtils.buildValueArray(parsed, params, null);
        String prepared = NamedParameterUtils.substituteNamedParameters(parsed, params);
        assertEquals(prepared.chars().filter(c -> c == '?').count(), bound.length);
    }
}
