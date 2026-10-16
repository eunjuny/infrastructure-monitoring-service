package dev.infrastructure.monitoring.resource;

import jakarta.persistence.EntityManagerFactory;
import jakarta.persistence.EntityManager;
import jakarta.persistence.FetchType;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.QueryTimeoutException;
import org.hibernate.resource.jdbc.spi.StatementInspector;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.autoconfigure.orm.jpa.HibernatePropertiesCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.orm.jpa.JpaTransactionManager;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import javax.sql.DataSource;
import java.util.ArrayList;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(properties = {"monitoring.mock.enabled=false", "spring.jpa.properties.hibernate.generate_statistics=true"})
@Testcontainers
@Import(JpaQueryIntegrationTest.SqlCaptureConfiguration.class)
class JpaQueryIntegrationTest {
    @Container static final PostgreSQLContainer<?> DB = new PostgreSQLContainer<>("postgres:17-alpine");
    @DynamicPropertySource static void database(DynamicPropertyRegistry properties) {
        properties.add("spring.datasource.url", DB::getJdbcUrl);
        properties.add("spring.datasource.username", DB::getUsername);
        properties.add("spring.datasource.password", DB::getPassword);
    }
    @Autowired DataSource source;
    @Autowired JdbcTemplate jdbc;
    @Autowired ResourceRepository repository;
    @Autowired ResourceService service;
    @Autowired EntityManagerFactory factory;
    @Autowired EntityManager entityManager;
    @Autowired PlatformTransactionManager transactions;
    @Autowired SqlCapture capture;
    Statistics statistics;

    @BeforeEach void prepare() {
        if (jdbc.queryForObject("select count(*) from infrastructure_pod", Long.class) == 0) {
            new ResourceDatabasePopulator(new ClassPathResource("seed.sql")).execute(source);
        }
        statistics = factory.unwrap(SessionFactory.class).getStatistics();
        statistics.clear();
        capture.statements.get().clear();
    }

    @Test void podListHasOneQueryAndNoEntityOrRelationshipLoading() {
        var rows = repository.list(ResourceQuery.list("POD", null, "", null, 100, null), null);
        assertEquals(101, rows.size());
        assertEquals(4, rows.getFirst().path().size());
        assertEquals(1, statistics.getPrepareStatementCount());
        assertEquals(1, statistics.getQueryExecutionCount());
        assertNoEntityGraph();
    }

    @Test void allTypeSearchHasAtMostFourQueriesAndOnlyLimitPlusOneRows() {
        var rows = repository.search(ResourceQuery.search(null, "pod", null, 100, null), null);
        assertEquals(101, rows.size());
        assertTrue(rows.stream().allMatch(row -> row.resource().type() == ResourceType.POD));
        assertEquals(4, statistics.getQueryExecutionCount());
        assertEquals(4, statistics.getPrepareStatementCount());
        assertNoEntityGraph();
    }

    @Test void validatedScopeHasFixedQueryCountRegardlessOfResultSize() {
        var result = service.list(ResourceQuery.list("POD", null, "", null, 100, null, 2L, 101L));
        assertEquals(50, result.items().size());
        assertEquals(3, statistics.getPrepareStatementCount());
        assertTrue(result.items().stream().allMatch(item -> item.parent().id() == 101));
        assertNoEntityGraph();
    }

    @Test void detailAndPathAreScalarProjectionsUsableAfterTransactionCloses() {
        var result = service.detail(ResourceType.POD, 1);
        assertEquals("worker-pod-000001", result.resource().name());
        assertEquals(4, result.path().size());
        assertEquals(1, result.attributes().get("restartCount"));
        assertEquals(1, statistics.getPrepareStatementCount());
        assertNoEntityGraph();
    }

    @Test void flywaySchemaIsMappedAndJpaOwnsTransactions() {
        assertInstanceOf(JpaTransactionManager.class, transactions);
        assertEquals(5, factory.getMetamodel().getEntities().size());
        assertEquals(3, jdbc.queryForObject("select max(version::integer) from flyway_schema_history where success", Integer.class));
        assertEquals(100000, jdbc.queryForObject("select count(*) from infrastructure_pod", Integer.class));
    }

    @Test void ancestorMappingsAreLazyAndCollectionFetchPaginationIsBlocked() throws Exception {
        for (var mapping : List.of(
                dev.infrastructure.monitoring.persistence.NodeResource.class.getDeclaredField("cluster"),
                dev.infrastructure.monitoring.persistence.NamespaceResource.class.getDeclaredField("node"),
                dev.infrastructure.monitoring.persistence.PodResource.class.getDeclaredField("namespace"))) {
            assertEquals(FetchType.LAZY, mapping.getAnnotation(ManyToOne.class).fetch());
        }
        assertEquals(false, factory.getProperties().get("hibernate.enable_lazy_load_no_trans") == null ? false
                : Boolean.parseBoolean(factory.getProperties().get("hibernate.enable_lazy_load_no_trans").toString()));
        assertEquals("true", String.valueOf(factory.getProperties().get("hibernate.query.fail_on_pagination_over_collection_fetch")));
    }

    @Test void generatedNameSearchSqlStillUsesTheTrigramIndex() {
        repository.list(ResourceQuery.list("POD", null, "worker-pod-000001", null, 100, null), null);
        assertEquals(1, capture.statements.get().size());
        String sql = capture.statements.get().getFirst();
        assertTrue(sql.contains("ilike"));
        assertFalse(sql.contains("lower("));
        List<String> plan = jdbc.query(connection -> {
            var statement = connection.prepareStatement("EXPLAIN (ANALYZE, BUFFERS) " + sql);
            statement.setString(1, "%worker-pod-000001%");
            statement.setInt(2, 101);
            return statement;
        }, (result, row) -> result.getString(1));
        assertTrue(String.join("\n", plan).contains("idx_pod_name_trgm"), String.join("\n", plan));
        System.out.println("JPA-generated selective search EXPLAIN:\n" + String.join("\n", plan));
    }

    @Test void jpaTimeoutHintCancelsARealPostgresqlQuery() {
        long started = System.nanoTime();
        assertThrows(QueryTimeoutException.class, () -> entityManager.createNativeQuery("select pg_sleep(3)")
                .setHint("jakarta.persistence.query.timeout", 1000).getResultList());
        assertTrue((System.nanoTime() - started) / 1_000_000 < 2500);
    }

    static class SqlCapture implements StatementInspector {
        final ThreadLocal<List<String>> statements = ThreadLocal.withInitial(ArrayList::new);
        public String inspect(String sql) { statements.get().add(sql); return sql; }
    }

    @TestConfiguration
    static class SqlCaptureConfiguration {
        @Bean SqlCapture sqlCapture() { return new SqlCapture(); }
        @Bean HibernatePropertiesCustomizer captureSql(SqlCapture capture) {
            return properties -> properties.put("hibernate.session_factory.statement_inspector", capture);
        }
    }

    private void assertNoEntityGraph() {
        assertEquals(0, statistics.getEntityLoadCount());
        assertEquals(0, statistics.getEntityFetchCount());
        assertEquals(0, statistics.getCollectionFetchCount());
    }
}
