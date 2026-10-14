package dev.infrastructure.monitoring.mock;

import dev.infrastructure.monitoring.resource.ResourceType;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.core.env.MapPropertySource;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterUtils;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class MockStatePolicyTest {
    @Test void schedulerIsAbsentByDefault() {
        try (var context = new AnnotationConfigApplicationContext()) {
            context.register(MockStateConfiguration.class); context.refresh();
            assertTrue(context.getBeansOfType(MockStateScheduler.class).isEmpty());
        }
    }

    @Test void explicitFalseAlsoKeepsSchedulerAbsent() {
        try (var context = new AnnotationConfigApplicationContext()) {
            context.getEnvironment().getPropertySources().addFirst(new MapPropertySource("test",
                    Map.of("monitoring.mock.enabled", "false")));
            context.register(MockStateConfiguration.class); context.refresh();
            assertTrue(context.getBeansOfType(MockStateScheduler.class).isEmpty());
        }
    }

    @Test void optInGateRequiresTheExactProperty() {
        var gate = MockStateConfiguration.class.getAnnotation(ConditionalOnProperty.class);
        assertArrayEquals(new String[]{"monitoring.mock.enabled"}, gate.name());
        assertEquals("true", gate.havingValue()); assertFalse(gate.matchIfMissing());
    }

    @Test void intervalsAreBounded() {
        assertThrows(IllegalArgumentException.class, () -> new MockStateScheduler(null, 0, 10000));
        assertThrows(IllegalArgumentException.class, () -> new MockStateScheduler(null, 60001, 10000));
        assertThrows(IllegalArgumentException.class, () -> new MockStateScheduler(null, 5000, -1));
        assertDoesNotThrow(() -> new MockStateScheduler(null, 5000, 10000));
    }

    @Test void SQLUsesPrimaryKeyCursorAndBoundedLockedRows() {
        var repository = new MockStateServiceTest.Store();
        for (var type : ResourceType.values()) {
            String sql = repository.selectionSql(type);
            assertTrue(sql.contains(type.table()));
            assertTrue(sql.contains("where id > :after order by id limit :limit for update"));
            assertFalse(sql.contains("offset")); assertFalse(sql.contains("count("));
        }
    }

    @Test void mutationSQLBindsIDsAndTimestampAndRetainsCounterLimits() {
        var repository = new MockStateServiceTest.Store();
        for (var type : ResourceType.values()) {
            String sql = repository.updateSql(type);
            var parsed = NamedParameterUtils.parseSqlStatement(sql);
            assertEquals(2, NamedParameterUtils.buildValueArray(parsed,
                    new MapSqlParameterSource("ids", List.of(1L, 2L)).addValue("changedAt", java.sql.Timestamp.from(java.time.Instant.EPOCH)), null).length);
            assertTrue(sql.contains("where id in (:ids)"));
            assertTrue(sql.contains("when 'NORMAL' then 'WARNING'"));
        }
        assertTrue(repository.updateSql(ResourceType.POD).contains("2147483647"));
        assertTrue(repository.updateSql(ResourceType.NODE).contains("least(100"));
    }
}
