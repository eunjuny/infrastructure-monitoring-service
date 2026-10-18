package dev.infrastructure.monitoring;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
class SchemaIntegrationTest {
    @Container
    static final PostgreSQLContainer<?> DB = new PostgreSQLContainer<>("postgres:17-alpine");

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry properties) {
        properties.add("spring.datasource.url", DB::getJdbcUrl);
        properties.add("spring.datasource.username", DB::getUsername);
        properties.add("spring.datasource.password", DB::getPassword);
    }

    @Autowired JdbcTemplate jdbc;
    @Autowired DataSource dataSource;
    @Autowired MockMvc mvc;

    @Test
    void flywaySeedHierarchyIdempotencyAndSummaryAreValid() throws Exception {
        Long tables = jdbc.queryForObject("""
                select count(*) from information_schema.tables
                where table_schema = 'public' and table_name in
                  ('infrastructure_cluster', 'infrastructure_node',
                   'infrastructure_namespace', 'infrastructure_pod')
                """, Long.class);
        assertThat(tables).isEqualTo(4L);
        assertThat(jdbc.queryForObject("select count(*) from infrastructure_pod", Long.class)).isZero();

        var seed = new ResourceDatabasePopulator(new ClassPathResource("seed.sql"));
        seed.execute(dataSource);
        assertExpectedCounts();
        assertThat(jdbc.queryForObject("select count(*) from infrastructure_pod_container", Long.class)).isGreaterThan(150000L);
        assertThat(jdbc.queryForObject("select count(*) from infrastructure_node_gpu", Long.class)).isEqualTo(40L);
        assertThat(jdbc.queryForObject("select count(*) from infrastructure_kubernetes_event", Long.class)).isGreaterThan(0L);
        assertThat(jdbc.queryForObject("""
                select count(*) from (
                  select cluster_id from infrastructure_node
                  group by cluster_id having count(*) <> 10
                ) unexpected
                """, Long.class)).isZero();
        assertThat(jdbc.queryForObject("""
                select count(*) from (
                  select node_id from infrastructure_namespace
                  group by node_id having count(*) <> 10
                ) unexpected
                """, Long.class)).isZero();
        assertThat(jdbc.queryForObject("""
                select count(*) from (
                  select namespace_id from infrastructure_pod
                  group by namespace_id having count(*) <> case when namespace_id <= 1000 then 100 else 50 end
                ) unexpected
                """, Long.class)).isZero();
        assertThat(jdbc.queryForObject("""
                select count(*) from infrastructure_pod p
                left join infrastructure_namespace ns on ns.id = p.namespace_id
                left join infrastructure_node n on n.id = ns.node_id
                left join infrastructure_cluster c on c.id = n.cluster_id
                where ns.id is null or n.id is null or c.id is null
                """, Long.class)).isZero();

        var snapshot = jdbc.queryForMap("""
                select count(*) as rows, sum(id) as ids, sum(restart_count) as restarts,
                       min(updated_at) as earliest, max(updated_at) as latest
                from infrastructure_pod
                """);
        seed.execute(dataSource);
        assertExpectedCounts();
        assertThat(jdbc.queryForObject("select count(*) from infrastructure_pod_container", Long.class)).isGreaterThan(150000L);
        assertThat(jdbc.queryForMap("""
                select count(*) as rows, sum(id) as ids, sum(restart_count) as restarts,
                       min(updated_at) as earliest, max(updated_at) as latest
                from infrastructure_pod
                """)).isEqualTo(snapshot);

        mvc.perform(get("/api/resources/summary"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.clusters").value(20))
                .andExpect(jsonPath("$.nodes").value(200))
                .andExpect(jsonPath("$.namespaces").value(2000))
                .andExpect(jsonPath("$.pods").value(150000));

        // Simulate upgrading an existing 100k installation without rewriting its current state.
        jdbc.update("delete from infrastructure_pod where id > 100000");
        jdbc.update("update infrastructure_pod set status='WARNING',restart_count=123 where id=1");
        var original = jdbc.queryForMap("select namespace_id,name,status,updated_at,restart_count from infrastructure_pod where id=1");
        seed.execute(dataSource);
        assertExpectedCounts();
        assertThat(jdbc.queryForMap("select namespace_id,name,status,updated_at,restart_count from infrastructure_pod where id=1"))
                .isEqualTo(original);
        assertThat(jdbc.queryForObject("select count(*) from infrastructure_pod where id <= 100000 and namespace_id <> ((id-1)/50)+1", Long.class))
                .isZero();
        assertThat(jdbc.queryForObject("select count(*) from infrastructure_pod where id > 100000 and namespace_id <> ((id-100001)/50)+1", Long.class))
                .isZero();
        assertThat(jdbc.queryForObject("select count(*) from (select namespace_id from infrastructure_pod group by namespace_id having count(*) <> case when namespace_id <= 1000 then 100 else 50 end) unexpected", Long.class))
                .isZero();

        // Reproduce the previous appended fixture layout; only the parent changes on upgrade.
        jdbc.update("update infrastructure_pod set namespace_id=((id-100001)/25)+1 where id > 100000");
        jdbc.update("update infrastructure_pod set status='WARNING',restart_count=321 where id=150000");
        var appendedState = jdbc.queryForMap("select id,name,status,updated_at,restart_count from infrastructure_pod where id=150000");
        seed.execute(dataSource);
        assertThat(jdbc.queryForMap("select id,name,status,updated_at,restart_count from infrastructure_pod where id=150000"))
                .isEqualTo(appendedState);
        assertThat(jdbc.queryForObject("select namespace_id from infrastructure_pod where id=150000", Long.class)).isEqualTo(1000L);
        assertThat(jdbc.queryForObject("select count(*) from infrastructure_pod where namespace_id<=1000", Long.class)).isEqualTo(100000L);
        assertThat(jdbc.queryForObject("select count(*) from infrastructure_pod where namespace_id>1000", Long.class)).isEqualTo(50000L);
        assertThat(jdbc.queryForMap("select namespace_id,name,status,updated_at,restart_count from infrastructure_pod where id=1"))
                .isEqualTo(original);
        seed.execute(dataSource);
        assertThat(jdbc.queryForMap("select id,name,status,updated_at,restart_count from infrastructure_pod where id=150000"))
                .isEqualTo(appendedState);
    }

    private void assertExpectedCounts() {
        assertThat(jdbc.queryForObject("select count(*) from infrastructure_cluster", Long.class)).isEqualTo(20L);
        assertThat(jdbc.queryForObject("select count(*) from infrastructure_node", Long.class)).isEqualTo(200L);
        assertThat(jdbc.queryForObject("select count(*) from infrastructure_namespace", Long.class)).isEqualTo(2000L);
        assertThat(jdbc.queryForObject("select count(*) from infrastructure_pod", Long.class)).isEqualTo(150000L);
    }
}
