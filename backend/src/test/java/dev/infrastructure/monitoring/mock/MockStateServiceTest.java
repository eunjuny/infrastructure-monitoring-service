package dev.infrastructure.monitoring.mock;

import dev.infrastructure.monitoring.resource.ResourceType;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.AbstractDataSource;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.Connection;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class MockStateServiceTest {
    private static final Instant NOW = Instant.parse("2026-10-13T05:00:00Z");

    static class Store extends MockStateRepository {
        record Query(ResourceType type, long after, int limit) { }
        final List<Query> queries = new ArrayList<>();
        int changes;
        boolean fail, empty;
        Store() {
            super(new AbstractDataSource() {
                @Override public Connection getConnection() throws SQLException { throw new SQLException("No database in unit test"); }
                @Override public Connection getConnection(String user, String password) throws SQLException { return getConnection(); }
            }, 3);
        }
        @Override public List<Long> nextIds(ResourceType type, long after, int limit) {
            queries.add(new Query(type, after, limit));
            if (empty || after >= 1000) return List.of();
            return java.util.stream.LongStream.rangeClosed(after + 1, after + limit).boxed().toList();
        }
        @Override public int change(ResourceType type, List<Long> ids, Instant changedAt) {
            assertEquals(NOW, changedAt);
            if (fail && type == ResourceType.POD) throw new IllegalStateException("Simulated failure");
            changes += ids.size(); return ids.size();
        }
    }

    static class Manager extends AbstractPlatformTransactionManager {
        int commits, rollbacks;
        @Override protected Object doGetTransaction() { return new Object(); }
        @Override protected void doBegin(Object transaction, TransactionDefinition definition) { }
        @Override protected void doCommit(DefaultTransactionStatus status) { commits++; }
        @Override protected void doRollback(DefaultTransactionStatus status) { rollbacks++; }
    }
    MockStateService service(Store store, Manager manager) {
        return new MockStateService(store, new TransactionTemplate(manager), Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @Test void tickChangesAtMost116RowsAndCommitsOnce() {
        var store = new Store(); var manager = new Manager();
        var result = service(store, manager).advance();
        assertEquals(116, result.changed().values().stream().mapToInt(Integer::intValue).sum());
        assertEquals(List.of(1, 5, 10, 100), store.queries.stream().map(Store.Query::limit).toList());
        assertEquals(1, manager.commits); assertEquals(0, manager.rollbacks);
        assertThrows(UnsupportedOperationException.class, () -> result.changed().put(ResourceType.POD, 0));
    }

    @Test void nextTickUsesIndependentTypeCursors() {
        var store = new Store(); var service = service(store, new Manager());
        service.advance(); store.queries.clear(); service.advance();
        assertEquals(List.of(1L, 5L, 10L, 100L), store.queries.stream().map(Store.Query::after).toList());
    }

    @Test void exhaustedTypeWrapsWithoutCountOrLoadingAllRows() {
        var store = new Store(); var service = service(store, new Manager());
        for (int i = 0; i < 10; i++) service.advance();
        store.queries.clear(); service.advance();
        var pods = store.queries.stream().filter(q -> q.type() == ResourceType.POD).toList();
        assertEquals(List.of(1000L, 0L), pods.stream().map(Store.Query::after).toList());
        assertTrue(pods.stream().allMatch(q -> q.limit() == 100));
    }

    @Test void rollbackDoesNotAdvanceAnyCursor() {
        var store = new Store(); var manager = new Manager(); var service = service(store, manager);
        store.fail = true;
        assertThrows(IllegalStateException.class, service::advance);
        assertEquals(0, manager.commits); assertEquals(1, manager.rollbacks);
        store.fail = false; store.queries.clear(); service.advance();
        assertTrue(store.queries.stream().allMatch(q -> q.after() == 0));
    }

    @Test void emptyDatabaseKeepsCursorsAtBeginningForLaterSeed() {
        var store = new Store(); var service = service(store, new Manager());
        store.empty = true;
        assertEquals(0, service.advance().changed().values().stream().mapToInt(Integer::intValue).sum());
        store.empty = false; store.queries.clear(); service.advance();
        assertTrue(store.queries.stream().allMatch(q -> q.after() == 0));
    }

    @Test void affectedRowMismatchRollsBackInsteadOfSkippingRows() {
        var store = new Store() {
            @Override public int change(ResourceType type, List<Long> ids, Instant changedAt) { return 0; }
        };
        var manager = new Manager();
        assertThrows(IllegalStateException.class, () -> service(store, manager).advance());
        assertEquals(1, manager.rollbacks);
    }
}
