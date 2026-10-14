package dev.infrastructure.monitoring.resource;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.AbstractDataSource;

import java.sql.Connection;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.LongStream;

import static dev.infrastructure.monitoring.resource.ResourceResponses.*;
import static org.junit.jupiter.api.Assertions.*;

class ResourceServiceTest {
    private final StubRepository repository = new StubRepository();
    private final ResourceService service = new ResourceService(repository);

    @Test void returnsOnlyRequestedSizeAndNextCursor() {
        repository.rows = LongStream.rangeClosed(1, 4).mapToObj(this::pod).toList();
        var query = ResourceQuery.list("POD", null, "", null, 3, null);
        var page = service.list(query);
        assertEquals(List.of(1L, 2L, 3L), page.items().stream().map(Item::id).toList());
        assertTrue(page.hasMore());
        assertEquals(new ResourceCursorCodec.Position(3, 3), new ResourceCursorCodec().decode(
                ResourceQuery.list("POD", null, "", null, 3, page.nextCursor())));
    }

    @Test void omitsCursorForFinalAndEmptyPages() {
        repository.rows = List.of(pod(1));
        var finalPage = service.list(ResourceQuery.list("POD", null, "", null, 3, null));
        assertFalse(finalPage.hasMore());
        assertNull(finalPage.nextCursor());
        repository.rows = List.of();
        assertTrue(service.list(ResourceQuery.list("POD", null, "", null, 3, null)).items().isEmpty());
    }

    @Test void passesDecodedPositionToRepository() {
        var q = ResourceQuery.list("POD", null, "", null, 3, null);
        String cursor = new ResourceCursorCodec().encode(q, new ResourceCursorCodec.Position(3, 77));
        service.list(ResourceQuery.list("POD", null, "", null, 3, cursor));
        assertEquals(77L, repository.position.id());
    }

    @Test void refusesNonexistentParentBeforeQueryingChildren() {
        repository.present = false;
        var error = assertThrows(ResourceRequestException.class, () -> service.children(ResourceType.NAMESPACE, 9, "", null, 50, null));
        assertEquals(ResourceRequestException.Reason.RESOURCE_NOT_FOUND, error.reason());
        assertEquals(0, repository.listCalls);
    }

    @Test void infersChildrenTypeAndParentScope() {
        service.children(ResourceType.NAMESPACE, 9, "worker", "ERROR", 50, null);
        assertEquals(ResourceType.POD, repository.query.type());
        assertEquals(9L, repository.query.parentId());
        assertEquals(ResourceType.NAMESPACE, repository.existenceType);
        assertEquals(ResourceStatus.ERROR, repository.query.status());
    }

    @Test void leafReturnsEmptyChildrenWithoutListQuery() {
        var page = service.children(ResourceType.POD, 1, "", null, 50, null);
        assertTrue(page.items().isEmpty());
        assertFalse(page.hasMore());
        assertEquals(0, repository.listCalls);
    }

    @Test void missingLeafIsStillNotFound() {
        repository.present = false;
        assertThrows(ResourceRequestException.class, () -> service.children(ResourceType.POD, 1, "", null, 50, null));
    }

    @Test void detailReturnsAttributesAndWholePath() {
        repository.found = Optional.of(pod(1));
        var result = service.detail(ResourceType.POD, 1);
        assertEquals(4, result.path().size());
        assertEquals(2, result.attributes().get("restartCount"));
        assertEquals(ResourceType.NAMESPACE, result.resource().parent().type());
        assertEquals(result.path(), service.path(ResourceType.POD, 1).items());
    }

    @Test void missingDetailAndInvalidIdAreRejected() {
        assertEquals(ResourceRequestException.Reason.RESOURCE_NOT_FOUND,
                assertThrows(ResourceRequestException.class, () -> service.detail(ResourceType.POD, 1)).reason());
        assertEquals(ResourceRequestException.Reason.INVALID_QUERY,
                assertThrows(ResourceRequestException.class, () -> service.detail(ResourceType.POD, 0)).reason());
    }

    @Test void globalSearchIncludesPathsAndPagination() {
        repository.rows = List.of(pod(1), pod(2));
        var page = service.search(ResourceQuery.search(null, "pod", null, 1, null));
        assertEquals(1, page.items().size());
        assertEquals(4, page.items().getFirst().path().size());
        assertTrue(page.hasMore());
        assertNotNull(page.nextCursor());
    }

    private ResourceRepository.Row pod(long id) {
        var ns = new Reference(ResourceType.NAMESPACE, 1, "namespace-0001");
        var item = new Item(ResourceType.POD, id, "pod-" + id, ResourceStatus.NORMAL,
                Instant.parse("2026-01-01T00:00:00Z"), ns, false);
        return new ResourceRepository.Row(item, Map.of("restartCount", 2), List.of(
                new Reference(ResourceType.CLUSTER, 1, "cluster-01"),
                new Reference(ResourceType.NODE, 1, "node-001"), ns,
                new Reference(ResourceType.POD, id, item.name())));
    }

    static final class StubRepository extends ResourceRepository {
        boolean present = true;
        int listCalls;
        ResourceType existenceType;
        ResourceQuery query;
        ResourceCursorCodec.Position position;
        List<Row> rows = List.of();
        Optional<Row> found = Optional.empty();

        StubRepository() {
            super(new AbstractDataSource() {
                @Override public Connection getConnection() throws SQLException { throw new SQLException("No database in unit test"); }
                @Override public Connection getConnection(String user, String pass) throws SQLException { return getConnection(); }
            }, 3);
        }

        @Override public boolean exists(ResourceType type, long id) { existenceType = type; return present; }
        @Override public Optional<Row> find(ResourceType type, long id) { return found; }
        @Override public List<Row> list(ResourceQuery q, ResourceCursorCodec.Position p) {
            listCalls++; query = q; position = p; return rows;
        }
        @Override public List<Row> search(ResourceQuery q, ResourceCursorCodec.Position p) {
            query = q; position = p; return rows;
        }
    }
}
