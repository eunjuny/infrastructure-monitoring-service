package dev.infrastructure.monitoring.resource;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
class ResourceApiIntegrationTest {
    @Container static final PostgreSQLContainer<?> DB = new PostgreSQLContainer<>("postgres:17-alpine");

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry properties) {
        properties.add("spring.datasource.url", DB::getJdbcUrl);
        properties.add("spring.datasource.username", DB::getUsername);
        properties.add("spring.datasource.password", DB::getPassword);
    }

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;
    @Autowired JdbcTemplate jdbc;
    @Autowired DataSource dataSource;

    @BeforeEach void seedOnce() {
        if (jdbc.queryForObject("select count(*) from infrastructure_pod", Long.class) == 0) {
            new ResourceDatabasePopulator(new ClassPathResource("seed.sql")).execute(dataSource);
        }
    }

    @Test void initialClustersHaveRequiredFieldsAndChildren() throws Exception {
        mvc.perform(get("/api/resources"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(20))
                .andExpect(jsonPath("$.items[0].type").value("CLUSTER"))
                .andExpect(jsonPath("$.items[0].name").value("cluster-01"))
                .andExpect(jsonPath("$.items[0].status").value("NORMAL"))
                .andExpect(jsonPath("$.items[0].updatedAt").isString())
                .andExpect(jsonPath("$.items[0].hasChildren").value(true))
                .andExpect(jsonPath("$.hasMore").value(false));
    }

    @Test void childrenAreScopedToChosenParent() throws Exception {
        mvc.perform(get("/api/resources/CLUSTER/1/children"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(10))
                .andExpect(jsonPath("$.items[0].type").value("NODE"))
                .andExpect(jsonPath("$.items[0].parent.id").value(1));
        mvc.perform(get("/api/resources/NODE/1/children"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(10))
                .andExpect(jsonPath("$.items[0].type").value("NAMESPACE"));
        mvc.perform(get("/api/resources/NAMESPACE/1/children"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(50))
                .andExpect(jsonPath("$.items[0].type").value("POD"));
    }

    @Test void globalPodListDoesNotReturnAllHundredThousandRows() throws Exception {
        mvc.perform(get("/api/resources").param("type", "POD").param("limit", "100"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(100))
                .andExpect(jsonPath("$.hasMore").value(true))
                .andExpect(jsonPath("$.nextCursor").isString());
    }

    @Test void cursorReturnsNextIdsWithoutRepeatedRows() throws Exception {
        JsonNode first = mapper.readTree(mvc.perform(get("/api/resources/NAMESPACE/1/children")
                        .param("limit", "20")).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString());
        mvc.perform(get("/api/resources/NAMESPACE/1/children")
                        .param("limit", "20").param("cursor", first.get("nextCursor").asText()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].id").value(21))
                .andExpect(jsonPath("$.items[19].id").value(40));
    }

    @Test void filterAndLiteralNameSearchAreCombined() throws Exception {
        mvc.perform(get("/api/resources").param("type", "POD")
                        .param("parentId", "1").param("q", "worker").param("status", "NORMAL"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].name").value("worker-pod-000001"))
                .andExpect(jsonPath("$.items[0].status").value("NORMAL"));
        mvc.perform(get("/api/resources/search").param("q", "%"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(0));
        mvc.perform(get("/api/resources/search").param("q", "' OR 1=1 --"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(0));
    }

    @Test void searchReturnsWholePathForMatchingPod() throws Exception {
        mvc.perform(get("/api/resources/search").param("q", "WORKER-POD-000001"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.items[0].resource.type").value("POD"))
                .andExpect(jsonPath("$.items[0].path.length()").value(4))
                .andExpect(jsonPath("$.items[0].path[0].name").value("cluster-01"))
                .andExpect(jsonPath("$.items[0].path[1].name").value("node-001"))
                .andExpect(jsonPath("$.items[0].path[2].name").value("namespace-0001"));
    }

    @Test void searchCursorCrossesHierarchyLevels() throws Exception {
        JsonNode first = mapper.readTree(mvc.perform(get("/api/resources/search")
                        .param("q", "01").param("limit", "1"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertEquals("CLUSTER", first.get("items").get(0).get("resource").get("type").asText());
        mvc.perform(get("/api/resources/search").param("q", "01").param("limit", "1")
                        .param("cursor", first.get("nextCursor").asText()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].resource.type").value("NODE"));
    }

    @Test void searchTypeAndStatusFiltersAreApplied() throws Exception {
        JsonNode result = mapper.readTree(mvc.perform(get("/api/resources/search")
                        .param("q", "pod").param("type", "POD").param("status", "ERROR"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertFalse(result.get("items").isEmpty());
        for (JsonNode item : result.get("items")) {
            assertEquals("POD", item.get("resource").get("type").asText());
            assertEquals("ERROR", item.get("resource").get("status").asText());
        }
    }

    @Test void detailAndPathExposeTypeSpecificInformation() throws Exception {
        mvc.perform(get("/api/resources/NODE/1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.attributes.cpuUsage").isNumber())
                .andExpect(jsonPath("$.attributes.memoryUsage").isNumber())
                .andExpect(jsonPath("$.path.length()").value(2));
        mvc.perform(get("/api/resources/POD/1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.attributes.restartCount").value(1))
                .andExpect(jsonPath("$.path.length()").value(4));
        mvc.perform(get("/api/resources/POD/1/path"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[3].type").value("POD"));
    }

    @Test void missingResourcesAre404AndExistingLeafIsEmpty() throws Exception {
        for (String suffix : new String[]{"", "/children", "/path"}) {
            mvc.perform(get("/api/resources/POD/999999" + suffix))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.code").value("RESOURCE_NOT_FOUND"));
        }
        mvc.perform(get("/api/resources/POD/1/children"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(0));
        mvc.perform(get("/api/resources").param("type", "POD").param("parentId", "999999"))
                .andExpect(status().isNotFound());
    }

    @Test void invalidRequestsAre400() throws Exception {
        mvc.perform(get("/api/resources").param("limit", "101")).andExpect(status().isBadRequest());
        mvc.perform(get("/api/resources").param("limit", "abc")).andExpect(status().isBadRequest());
        mvc.perform(get("/api/resources").param("type", "unknown")).andExpect(status().isBadRequest());
        mvc.perform(get("/api/resources").param("status", "unknown")).andExpect(status().isBadRequest());
        mvc.perform(get("/api/resources").param("parentId", "1")).andExpect(status().isBadRequest());
        mvc.perform(get("/api/resources/search")).andExpect(status().isBadRequest());
        mvc.perform(get("/api/resources/search").param("q", " ")).andExpect(status().isBadRequest());
        mvc.perform(get("/api/resources/POD/0")).andExpect(status().isBadRequest());
        mvc.perform(get("/api/resources/POD/abc")).andExpect(status().isBadRequest());
    }

    @Test void changedFilterAndMalformedCursorAre400() throws Exception {
        JsonNode first = mapper.readTree(mvc.perform(get("/api/resources")
                        .param("type", "POD").param("limit", "1"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        mvc.perform(get("/api/resources").param("type", "POD").param("status", "ERROR")
                        .param("cursor", first.get("nextCursor").asText()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.field").value("cursor"));
        mvc.perform(get("/api/resources").param("type", "POD").param("cursor", "not%base64"))
                .andExpect(status().isBadRequest());
    }
}
