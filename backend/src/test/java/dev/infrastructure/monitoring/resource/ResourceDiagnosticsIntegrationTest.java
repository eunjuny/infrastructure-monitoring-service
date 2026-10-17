package dev.infrastructure.monitoring.resource;

import java.sql.DriverManager;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(properties = "monitoring.mock.enabled=false")
@Testcontainers
class ResourceDiagnosticsIntegrationTest {
    @Container static final PostgreSQLContainer<?> DB = new PostgreSQLContainer<>("postgres:17-alpine");
    @DynamicPropertySource static void database(DynamicPropertyRegistry properties) {
        properties.add("spring.datasource.url", DB::getJdbcUrl);
        properties.add("spring.datasource.username", DB::getUsername);
        properties.add("spring.datasource.password", DB::getPassword);
    }
    @Autowired DataSource source;
    @Autowired JdbcTemplate jdbc;
    @Autowired ResourceService service;

    @BeforeEach void seed() {
        if (jdbc.queryForObject("select count(*) from infrastructure_pod", Long.class) == 0) {
            new ResourceDatabasePopulator(new ClassPathResource("seed.sql")).execute(source);
        }
    }

    @Test void clusterAggregatesAllDescendantsRatherThanTheVisiblePage() {
        var data = service.detailWithDiagnostics(ResourceType.CLUSTER, 1).attributes();
        assertEquals(10L, data.get("nodeCount"));
        assertEquals(10000L, data.get("podCount"));
        assertEquals(jdbc.queryForObject("select count(*) from infrastructure_node where cluster_id=1 and status <> 'ERROR'", Long.class), data.get("readyNodeCount"));
        assertEquals(jdbc.queryForObject("select count(*) from infrastructure_pod p join infrastructure_namespace ns on ns.id=p.namespace_id join infrastructure_node n on n.id=ns.node_id where n.cluster_id=1 and p.status <> 'NORMAL'", Long.class), data.get("problemPodCount"));
    }

    @Test void namespaceCountsAreScopedAndErrorsAreASubsetOfNotReady() {
        var data = service.detailWithDiagnostics(ResourceType.NAMESPACE, 1001).attributes();
        assertEquals(50L, data.get("podCount"));
        assertEquals(jdbc.queryForObject("select count(*) from infrastructure_pod where namespace_id=1001 and status='ERROR'", Long.class), data.get("errorPodCount"));
        assertEquals(jdbc.queryForObject("select count(*) from infrastructure_pod where namespace_id=1001 and status <> 'NORMAL'", Long.class), data.get("notReadyPodCount"));
        assertTrue((long) data.get("errorPodCount") <= (long) data.get("notReadyPodCount"));
    }

    @Test void emptyExistingNamespaceHasRealZeroCounts() {
        jdbc.update("insert into infrastructure_namespace(id,node_id,name,status,updated_at) values(9000,1,'empty-fixture','NORMAL',now())");
        try {
            var data = service.detailWithDiagnostics(ResourceType.NAMESPACE, 9000).attributes();
            assertEquals(0L, data.get("podCount"));
            assertEquals(0L, data.get("errorPodCount"));
            assertEquals(0L, data.get("notReadyPodCount"));
        } finally { jdbc.update("delete from infrastructure_namespace where id=9000"); }
    }

    @Test void nodeAndPodDiagnosticsFollowTheLatestStoredState() {
        jdbc.update("update infrastructure_node set status='ERROR' where id=2");
        assertEquals(false, service.detailWithDiagnostics(ResourceType.NODE, 2).attributes().get("ready"));
        assertEquals(24, service.detailWithDiagnostics(ResourceType.NODE, 2).attributes().get("cpuCapacityCores"));
        jdbc.update("update infrastructure_pod set status='ERROR' where id=2");
        var failed = service.detailWithDiagnostics(ResourceType.POD, 2).attributes();
        assertEquals("Running", failed.get("phase"));
        assertEquals("CrashLoopBackOff", failed.get("failureReason"));
        assertEquals("OOMKilled", failed.get("lastTerminationReason"));
        assertTrue(failed.get("image").toString().startsWith("registry.example.invalid/mock/"));
        jdbc.update("update infrastructure_pod set status='NORMAL' where id=2");
        var recovered = service.detailWithDiagnostics(ResourceType.POD, 2).attributes();
        assertEquals(recovered.get("containerCount"), recovered.get("readyContainerCount"));
        assertNull(recovered.get("failureReason"));
    }

    @Test void missingResourceDoesNotBecomeAnEmptySuccessfulAggregate() {
        assertThrows(ResourceRequestException.class, () -> service.detailWithDiagnostics(ResourceType.CLUSTER, 99999));
        assertThrows(ResourceRequestException.class, () -> service.detailWithDiagnostics(ResourceType.NAMESPACE, 99999));
    }

    @Test void v4MigratesExistingRowsWithoutChangingTheirRuntimeState() throws Exception {
        try (var connection = DriverManager.getConnection(DB.getJdbcUrl(), DB.getUsername(), DB.getPassword());
             var statement = connection.createStatement()) {
            statement.execute("create database diagnostic_upgrade");
        }
        String url = DB.getJdbcUrl().replace("/" + DB.getDatabaseName(), "/diagnostic_upgrade");
        Flyway.configure().dataSource(url, DB.getUsername(), DB.getPassword()).target("3").load().migrate();
        try (var connection = DriverManager.getConnection(url, DB.getUsername(), DB.getPassword());
             var statement = connection.createStatement()) {
            statement.execute("insert into infrastructure_cluster values(1,'legacy-cluster','NORMAL','2026-01-01T00:00:00Z')");
            statement.execute("insert into infrastructure_node values(1,1,'legacy-node','WARNING','2026-01-01T00:00:00Z',90,95)");
            statement.execute("insert into infrastructure_namespace values(1,1,'legacy-namespace','NORMAL','2026-01-01T00:00:00Z')");
            statement.execute("insert into infrastructure_pod values(1,1,'legacy-pod','ERROR','2026-01-01T00:00:00Z',17)");
        }
        Flyway.configure().dataSource(url, DB.getUsername(), DB.getPassword()).load().migrate();
        try (var connection = DriverManager.getConnection(url, DB.getUsername(), DB.getPassword());
             var statement = connection.createStatement();
             var result = statement.executeQuery("select namespace_id,name,status,restart_count,updated_at::text,container_count,image from infrastructure_pod where id=1")) {
            assertTrue(result.next());
            assertEquals(1, result.getInt(1));
            assertEquals("legacy-pod", result.getString(2));
            assertEquals("ERROR", result.getString(3));
            assertEquals(17, result.getInt(4));
            assertTrue(result.getString(5).startsWith("2026-01-01"));
            assertEquals(1, result.getInt(6));
            assertTrue(result.getString(7).contains("mock/worker"));
        }
    }
}
