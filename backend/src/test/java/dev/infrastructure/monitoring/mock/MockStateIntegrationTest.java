package dev.infrastructure.monitoring.mock;

import dev.infrastructure.monitoring.resource.ResourceType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import javax.sql.DataSource;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties = "monitoring.mock.enabled=false")
@AutoConfigureMockMvc
@Testcontainers
class MockStateIntegrationTest {
    @Container static final PostgreSQLContainer<?> DB = new PostgreSQLContainer<>("postgres:17-alpine");
    @DynamicPropertySource static void database(DynamicPropertyRegistry properties) {
        properties.add("spring.datasource.url", DB::getJdbcUrl);
        properties.add("spring.datasource.username", DB::getUsername);
        properties.add("spring.datasource.password", DB::getPassword);
    }
    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired DataSource source;
    @Autowired MockStateRepository repository;
    @Autowired org.springframework.transaction.PlatformTransactionManager manager;

    @BeforeEach void prepare() {
        if (jdbc.queryForObject("select count(*) from infrastructure_pod", Long.class) == 0) {
            new ResourceDatabasePopulator(new ClassPathResource("seed.sql")).execute(source);
        }
        jdbc.update("update infrastructure_pod set status='NORMAL', restart_count=0 where id <= 300");
        jdbc.update("update infrastructure_node set status='NORMAL', cpu_usage=98, memory_usage=99 where id <= 10");
    }

    MockStateService service(MockStateRepository store) {
        return new MockStateService(store, new org.springframework.transaction.support.TransactionTemplate(manager),
                Clock.fixed(Instant.parse("2030-01-01T00:00:00Z"), ZoneOffset.UTC));
    }

    @Test void onlyBoundedRowsChangeAndReadAPIsSeeCommittedValues() throws Exception {
        var result = service(repository).advance();
        assertEquals(100, result.changed().get(ResourceType.POD));
        assertEquals(100000, jdbc.queryForObject("select count(*) from infrastructure_pod", Integer.class));
        assertEquals("NORMAL", jdbc.queryForObject("select status from infrastructure_pod where id=101", String.class));
        mvc.perform(get("/api/resources/POD/1"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.resource.status").value("WARNING"))
                .andExpect(jsonPath("$.resource.updatedAt").value("2030-01-01T00:00:00Z"));
        mvc.perform(get("/api/resources/NODE/1"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.attributes.cpuUsage").value(4))
                .andExpect(jsonPath("$.attributes.memoryUsage").value(9));
    }

    @Test void repeatedTickAdvancesCursorAndPreservesParentRelations() {
        var service = service(repository); service.advance(); service.advance();
        assertEquals("WARNING", jdbc.queryForObject("select status from infrastructure_pod where id=200", String.class));
        assertEquals("NORMAL", jdbc.queryForObject("select status from infrastructure_pod where id=201", String.class));
        assertEquals(0, jdbc.queryForObject("select count(*) from infrastructure_pod p left join infrastructure_namespace n on n.id=p.namespace_id where n.id is null", Integer.class));
    }

    @Test void errorTransitionIncrementsRestartWithoutOverflow() {
        jdbc.update("update infrastructure_pod set status='WARNING', restart_count=2147483647 where id=1");
        service(repository).advance();
        assertEquals("ERROR", jdbc.queryForObject("select status from infrastructure_pod where id=1", String.class));
        assertEquals(Integer.MAX_VALUE, jdbc.queryForObject("select restart_count from infrastructure_pod where id=1", Integer.class));
        assertEquals(0, jdbc.queryForObject("select restart_count from infrastructure_pod where id=2", Integer.class));
    }

    @Test void failedTickRollsBackEarlierTypesAndRetriesTheSameIDs() {
        String before = jdbc.queryForObject("select status from infrastructure_cluster where id=1", String.class);
        var failure = new MockStateRepository(source, 3) {
            boolean fail = true;
            @Override public int change(ResourceType type, List<Long> ids, Instant time) {
                if (fail && type == ResourceType.POD) { fail = false; throw new IllegalStateException("Test failure"); }
                return super.change(type, ids, time);
            }
        };
        var service = service(failure);
        assertThrows(IllegalStateException.class, service::advance);
        assertEquals(before, jdbc.queryForObject("select status from infrastructure_cluster where id=1", String.class));
        assertEquals("NORMAL", jdbc.queryForObject("select status from infrastructure_node where id=1", String.class));
        service.advance();
        assertEquals("WARNING", jdbc.queryForObject("select status from infrastructure_pod where id=1", String.class));
    }
}
