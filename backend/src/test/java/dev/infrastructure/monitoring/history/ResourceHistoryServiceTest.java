package dev.infrastructure.monitoring.history;

import dev.infrastructure.monitoring.resource.ResourceRequestException;
import dev.infrastructure.monitoring.resource.ResourceService;
import dev.infrastructure.monitoring.resource.ResourceType;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.stream.LongStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class ResourceHistoryServiceTest {
    private final ResourceHistoryRepository history = mock(ResourceHistoryRepository.class);
    private final ResourceService resources = mock(ResourceService.class);
    private final ResourceHistoryService service = new ResourceHistoryService(history, resources, 24, 20000);

    @Test void checksAccessBeforeFetchingOnlyLimitPlusOneEvents() {
        when(history.list(ResourceType.POD, 1, 10L, 3)).thenReturn(List.of(event(9), event(8), event(7)));

        var page = service.list("POD", 1, 2, 10L);

        var order = inOrder(resources, history);
        order.verify(resources).detail(ResourceType.POD, 1);
        order.verify(history).list(ResourceType.POD, 1, 10L, 3);
        order.verifyNoMoreInteractions();
        assertEquals(List.of(9L, 8L), page.items().stream().map(ResourceHistory.Event::id).toList());
        assertTrue(page.hasMore());
        assertEquals(8L, page.nextBeforeId());
        assertEquals(24, page.retentionHours());
        assertEquals(20000, page.maximumEvents());
    }

    @Test void finalAndEmptyPagesHaveNoNextCursor() {
        when(history.list(ResourceType.NODE, 1, null, 3))
                .thenReturn(List.of(event(1)))
                .thenReturn(List.of());

        var last = service.list("NODE", 1, 2, null);
        assertEquals(1, last.items().size());
        assertFalse(last.hasMore());
        assertNull(last.nextBeforeId());

        var empty = service.list("NODE", 1, 2, null);
        assertTrue(empty.items().isEmpty());
        assertFalse(empty.hasMore());
        assertNull(empty.nextBeforeId());
    }

    @Test void maximumPageReads101RowsAndReturns100() {
        when(history.list(ResourceType.POD, 1, null, 101)).thenReturn(
                LongStream.iterate(101, id -> id - 1).limit(101).mapToObj(this::event).toList());

        var page = service.list("POD", 1, 100, null);

        assertEquals(100, page.items().size());
        assertEquals(2L, page.nextBeforeId());
        assertTrue(page.hasMore());
        verify(history).list(ResourceType.POD, 1, null, 101);
    }

    @Test void rejectsInvalidPaginationBeforeAnyResourceOrHistoryRead() {
        for (int limit : List.of(0, -1, 101)) {
            assertInvalidPagination(limit, null);
        }
        for (long beforeId : List.of(0L, -1L)) {
            assertInvalidPagination(20, beforeId);
        }
        verifyNoInteractions(resources, history);
    }

    @Test void rejectsUnknownTypeBeforeAnyResourceOrHistoryRead() {
        assertThrows(ResourceRequestException.class, () -> service.list("unknown", 1, 20, null));
        verifyNoInteractions(resources, history);
    }

    @Test void missingOrInaccessibleResourceCannotReadHistory() {
        var denied = ResourceRequestException.notFound();
        doThrow(denied).when(resources).detail(ResourceType.POD, 1);

        assertSame(denied, assertThrows(ResourceRequestException.class,
                () -> service.list("POD", 1, 20, null)));
        verifyNoInteractions(history);
    }

    private void assertInvalidPagination(int limit, Long beforeId) {
        var error = assertThrows(ResourceRequestException.class,
                () -> service.list("POD", 1, limit, beforeId));
        assertEquals(ResourceRequestException.Reason.INVALID_QUERY, error.reason());
        assertEquals("events", error.field());
    }

    private ResourceHistory.Event event(long id) {
        return new ResourceHistory.Event(id, "POD", 1, Instant.EPOCH,
                "NORMAL", "WARNING", "MOCK_SIMULATOR", null, null);
    }
}
