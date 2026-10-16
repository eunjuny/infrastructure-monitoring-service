package dev.infrastructure.monitoring.resource;

import org.junit.jupiter.api.Test;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import static org.junit.jupiter.api.Assertions.*;

/** HQL construction checks only; PostgreSQL integration tests verify generated SQL and results. */
class ResourceRepositoryQueryTest {
    private final ResourceRepository repository = new ResourceServiceTest.StubRepository();

    @Test void usesParentAndIdKeysetInsteadOfOffset() {
        var request = ResourceQuery.list("POD", 1L, "", null, 100, null);
        var position = new ResourceCursorCodec.Position(3, 500);
        String hql = repository.listHql(request, request.type(), position);
        assertTrue(hql.contains("r.namespace.id = :parentId"));
        assertTrue(hql.contains("r.id > :afterId"));
        assertTrue(hql.endsWith("order by r.id"));
        assertFalse(hql.toLowerCase().contains("offset"));
        assertBinding(request, ResourceType.POD, position);
    }

    @Test void keepsClientTextOutOfHqlAndEscapesWildcards() {
        var request = ResourceQuery.list("POD", 1L, "' OR 1=1 !%_", "ERROR", 50, null);
        String hql = repository.listHql(request, request.type(), null);
        assertFalse(hql.contains(request.query()));
        assertTrue(hql.contains("r.name ilike :pattern escape '!'"));
        assertEquals(request.likePattern(), repository.parameters(request, ResourceType.POD, null).get("pattern"));
        assertEquals(ResourceStatus.ERROR, repository.parameters(request, ResourceType.POD, null).get("status"));
        assertBinding(request, ResourceType.POD, null);
    }

    @Test void allTypesUseScalarProjectionsInsteadOfFetchJoins() {
        for (ResourceType type : ResourceType.values()) {
            var request = ResourceQuery.search(null, "pod", null, 50, null);
            String hql = repository.listHql(request, type, null);
            assertTrue(hql.startsWith("select new dev.infrastructure.monitoring.resource.ResourceProjection("));
            assertTrue(hql.contains("from " + ResourceRepository.entityName(type) + " r"));
            assertFalse(hql.contains("join fetch"));
            assertFalse(hql.contains("count("));
            assertBinding(request, type, null);
        }
    }

    @Test void typedSearchDoesNotLoadUnrelatedResourceGraphs() {
        var request = ResourceQuery.search("NODE", "node", "NORMAL", 10, null);
        String hql = repository.listHql(request, request.type(), null);
        assertTrue(hql.contains("from NodeResource r join r.cluster c"));
        assertFalse(hql.contains("PodResource"));
        assertBinding(request, request.type(), null);
    }

    @Test void cursorOnlyAppliesIdWithinItsOwnDepth() {
        var request = ResourceQuery.search(null, "01", "ERROR", 50, null);
        var position = new ResourceCursorCodec.Position(1, 9);
        assertTrue(repository.listHql(request, ResourceType.NODE, position).contains("r.id > :afterId"));
        assertFalse(repository.listHql(request, ResourceType.POD, position).contains(":afterId"));
        assertBinding(request, ResourceType.NODE, position);
        assertBinding(request, ResourceType.POD, position);
    }

    @Test void everyTypeAndScopeUsesExactlyItsBoundParameters() {
        for (ResourceType type : ResourceType.values()) {
            Long parent = type.parent() == null ? null : 1L;
            assertBinding(ResourceQuery.list(type.name(), parent, "name", "WARNING", 20, null), type, null);
            assertBinding(ResourceQuery.list(type.name(), null, "", null, 20, null), type, null);
        }
        assertBinding(ResourceQuery.list("POD", null, "pod", "ERROR", 20, null, 2L, 101L), ResourceType.POD, null);
    }

    private void assertBinding(ResourceQuery request, ResourceType type, ResourceCursorCodec.Position position) {
        Set<String> names = Pattern.compile(":([A-Za-z]+)").matcher(repository.listHql(request, type, position))
                .results().map(match -> match.group(1)).collect(Collectors.toSet());
        assertEquals(names, repository.parameters(request, type, position).keySet());
    }
}
