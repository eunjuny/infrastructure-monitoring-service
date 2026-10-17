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

    @Test void historyPagesRemainDescendingAndExposeTheSameContract() throws Exception {
        jdbc.update("delete from resource_change_event where resource_type='POD' and resource_id=1");
        for (int index = 0; index < 3; index++) {
            jdbc.update("insert into resource_change_event(resource_type,resource_id,occurred_at,"
                    + "previous_status,next_status,source,before_attributes,after_attributes)"
                    + " values ('POD',1,now(),'NORMAL','WARNING','MOCK_SIMULATOR','{}','{}')");
        }
        var first = mapper.readTree(mvc.perform(get("/api/resources/POD/1/events").param("limit", "2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(2))
                .andExpect(jsonPath("$.items[0].resourceType").value("POD"))
                .andExpect(jsonPath("$.items[0].resourceId").value(1))
                .andExpect(jsonPath("$.items[0].previousStatus").value("NORMAL"))
                .andExpect(jsonPath("$.items[0].nextStatus").value("WARNING"))
                .andExpect(jsonPath("$.retentionHours").value(24))
                .andExpect(jsonPath("$.maximumEvents").value(20000))
                .andExpect(jsonPath("$.hasMore").value(true))
                .andReturn().getResponse().getContentAsString());
        long cursor = first.path("nextBeforeId").asLong();
        assertTrue(first.path("items").get(0).path("id").asLong() > cursor);
        assertEquals(cursor, first.path("items").get(1).path("id").asLong());

        var last = mapper.readTree(mvc.perform(get("/api/resources/POD/1/events")
                        .param("limit", "2").param("beforeId", Long.toString(cursor)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.hasMore").value(false))
                .andExpect(jsonPath("$.nextBeforeId").isEmpty())
                .andReturn().getResponse().getContentAsString());
        assertTrue(last.path("items").get(0).path("id").asLong() < cursor);
    }

    @Test void historyPreservesEmptyNotFoundAndInvalidRequestResponses() throws Exception {
        mvc.perform(get("/api/resources/POD/100000/events"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(0))
                .andExpect(jsonPath("$.hasMore").value(false))
                .andExpect(jsonPath("$.nextBeforeId").isEmpty());
        mvc.perform(get("/api/resources/POD/999999/events"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("RESOURCE_NOT_FOUND"));
        mvc.perform(get("/api/resources/POD/0/events")).andExpect(status().isBadRequest());
        mvc.perform(get("/api/resources/unknown/1/events")).andExpect(status().isBadRequest());
        for (String limit : new String[]{"0", "101", "abc"}) {
            mvc.perform(get("/api/resources/POD/1/events").param("limit", limit))
                    .andExpect(status().isBadRequest());
        }
        mvc.perform(get("/api/resources/POD/1/events").param("beforeId", "0"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.field").value("events"));
    }

    @Test void scopesDescendantsByClusterAndNamespace() throws Exception {
        mvc.perform(get("/api/resources").param("type","POD").param("clusterId","2").param("limit","100"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.items[0].id").value(5001));
        mvc.perform(get("/api/resources").param("type","POD").param("clusterId","2").param("namespaceId","101"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.items.length()").value(50)).andExpect(jsonPath("$.items[0].parent.id").value(101));
        mvc.perform(get("/api/resources/search").param("q","worker").param("clusterId","2").param("namespaceId","101").param("status","NORMAL"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.items[0].resource.id").value(5001)).andExpect(jsonPath("$.items[0].path[0].id").value(2));
    }

    @Test void appendedPodsKeepOldParentsAndRemainSearchableAndPageable() throws Exception {
        var first = mapper.readTree(mvc.perform(get("/api/resources/NAMESPACE/1000/children"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(50))
                .andExpect(jsonPath("$.items[0].id").value(49951))
                .andExpect(jsonPath("$.items[49].id").value(50000))
                .andExpect(jsonPath("$.hasMore").value(true))
                .andReturn().getResponse().getContentAsString());
        mvc.perform(get("/api/resources/NAMESPACE/1000/children").param("cursor", first.path("nextCursor").asText()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(50))
                .andExpect(jsonPath("$.items[0].id").value(149951))
                .andExpect(jsonPath("$.items[49].id").value(150000))
                .andExpect(jsonPath("$.hasMore").value(false));
        mvc.perform(get("/api/resources/search").param("type", "POD").param("q", "api-pod-150000"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.items[0].resource.id").value(150000))
                .andExpect(jsonPath("$.items[0].path[0].id").value(10))
                .andExpect(jsonPath("$.items[0].path[2].id").value(1000));
        mvc.perform(get("/api/resources/NAMESPACE/2000/children"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.items.length()").value(50))
                .andExpect(jsonPath("$.hasMore").value(false));
    }

    @Test void rejectsConflictingUnknownAndIllegalScope() throws Exception {
        mvc.perform(get("/api/resources").param("type","POD").param("clusterId","1").param("namespaceId","101")).andExpect(status().isBadRequest());
        mvc.perform(get("/api/resources").param("type","NODE").param("namespaceId","1")).andExpect(status().isBadRequest());
        mvc.perform(get("/api/resources").param("type","POD").param("clusterId","999")).andExpect(status().isNotFound());
        mvc.perform(get("/api/resources").param("type","POD").param("parentId","1").param("namespaceId","101")).andExpect(status().isBadRequest());
    }

    @Test void cursorIsBoundToAncestorScopes() throws Exception {
        var first=mapper.readTree(mvc.perform(get("/api/resources").param("type","POD").param("clusterId","2").param("limit","20"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        var cursor=first.path("nextCursor").asText();
        mvc.perform(get("/api/resources").param("type","POD").param("clusterId","2").param("limit","20").param("cursor",cursor))
                .andExpect(status().isOk()).andExpect(jsonPath("$.items[0].id").value(5021));
        mvc.perform(get("/api/resources").param("type","POD").param("clusterId","3").param("cursor",cursor)).andExpect(status().isBadRequest());
        mvc.perform(get("/api/resources").param("type","POD").param("cursor",cursor)).andExpect(status().isBadRequest());
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
