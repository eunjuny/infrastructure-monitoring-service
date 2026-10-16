package dev.infrastructure.monitoring.resource;

import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.MockMvcPrint;
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
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** Holds the real pool connection; no mocked repository or replacement transaction manager. */
@SpringBootTest(properties = {"spring.datasource.hikari.maximum-pool-size=2", "monitoring.mock.enabled=false"})
@AutoConfigureMockMvc(print = MockMvcPrint.NONE)
@Testcontainers
class ConnectionPoolIntegrationTest {
    @Container static final PostgreSQLContainer<?> DB = new PostgreSQLContainer<>("postgres:17-alpine");

    @DynamicPropertySource static void database(DynamicPropertyRegistry properties) {
        properties.add("spring.datasource.url", DB::getJdbcUrl);
        properties.add("spring.datasource.username", DB::getUsername);
        properties.add("spring.datasource.password", DB::getPassword);
    }

    @Autowired DataSource source;
    @Autowired JdbcTemplate jdbc;
    @Autowired MockMvc mvc;

    @BeforeEach void seedOnce() {
        if (jdbc.queryForObject("select count(*) from infrastructure_pod", Long.class) == 0) {
            new ResourceDatabasePopulator(new ClassPathResource("seed.sql")).execute(source);
        }
    }

    @Test void productionDefaultsBoundConnectionAcquisitionAndValidation() {
        var pool = assertInstanceOf(HikariDataSource.class, source);
        assertEquals(3000, pool.getConnectionTimeout());
        assertEquals(1000, pool.getValidationTimeout());
    }

    @Test void exhaustedPoolReturnsSafe503AndQueriesRecoverAfterRelease() throws Exception {
        var pool = assertInstanceOf(HikariDataSource.class, source);
        long timeout = pool.getConnectionTimeout();
        pool.setConnectionTimeout(350); // Faster fault fixture; production defaults have a separate assertion.
        try {
            try (var held = pool.getConnection(); var secondHeld = pool.getConnection()) {
                assertFalse(held.isClosed());
                assertFalse(secondHeld.isClosed());
                for (String path : new String[]{"/api/resources/summary", "/api/resources?type=POD"}) {
                    long started = System.nanoTime();
                    String body = mvc.perform(get(path)).andExpect(status().isServiceUnavailable())
                            .andExpect(jsonPath("$.code").value("STORAGE_UNAVAILABLE"))
                            .andReturn().getResponse().getContentAsString();
                    assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started) < 5000,
                            "Pool rejection must be bounded; not a browser performance/SLA assertion");
                    assertFalse(body.contains("jdbc:"));
                    assertFalse(body.contains("HikariPool"));
                    assertFalse(body.contains(DB.getPassword()));
                }
            }
            mvc.perform(get("/api/resources/summary")).andExpect(status().isOk())
                    .andExpect(jsonPath("$.pods").value(100000));
            mvc.perform(get("/api/resources?type=POD")).andExpect(status().isOk())
                    .andExpect(jsonPath("$.items.length()").value(50));
        } finally {
            pool.setConnectionTimeout(timeout);
        }
    }
}
