package dev.infrastructure.monitoring.resource;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static dev.infrastructure.monitoring.resource.ResourceResponses.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

/** Actual MVC routing and serialization, with a stub repository and no DB. */
class ResourceControllerTest {
    private final ResourceServiceTest.StubRepository repository = new ResourceServiceTest.StubRepository();
    private final ObjectMapper mapper = Jackson2ObjectMapperBuilder.json()
            .modules(new JavaTimeModule()).featuresToDisable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS).build();
    private MockMvc mvc;

    @BeforeEach void setup() {
        var ns = new Reference(ResourceType.NAMESPACE, 1, "namespace-0001");
        var pod = new Item(ResourceType.POD, 1, "worker-pod-000001", ResourceStatus.NORMAL,
                Instant.parse("2026-01-01T00:00:00Z"), ns, false);
        var row = new ResourceRepository.Row(pod, Map.of("restartCount", 1), List.of(
                new Reference(ResourceType.CLUSTER, 1, "cluster-01"),
                new Reference(ResourceType.NODE, 1, "node-001"), ns,
                new Reference(ResourceType.POD, 1, pod.name())));
        repository.rows = List.of(row);
        repository.found = Optional.of(row);
        mvc = MockMvcBuilders.standaloneSetup(new ResourceController(new ResourceService(repository)))
                .setControllerAdvice(new ResourceExceptionHandler())
                .setMessageConverters(new MappingJackson2HttpMessageConverter(mapper)).build();
    }

    @Test void listSerializesRequiredFields() throws Exception {
        JsonNode body = request(get("/api/resources").param("type", "pod"), 200);
        assertEquals("POD", body.at("/items/0/type").asText());
        assertEquals("NORMAL", body.at("/items/0/status").asText());
        assertEquals("2026-01-01T00:00:00Z", body.at("/items/0/updatedAt").asText());
        assertEquals(1, body.at("/items/0/parent/id").asInt());
        assertTrue(body.get("nextCursor").isNull());
    }

    @Test void detailAndPathUseDifferentResponseShapes() throws Exception {
        assertEquals(1, request(get("/api/resources/POD/1"), 200).at("/attributes/restartCount").asInt());
        assertEquals(4, request(get("/api/resources/POD/1/path"), 200).get("items").size());
    }

    @Test void searchRouteIsNotMistakenForAResourceType() throws Exception {
        JsonNode body = request(get("/api/resources/search").param("q", "pod"), 200);
        assertEquals(4, body.at("/items/0/path").size());
    }

    @Test void childRouteInfersType() throws Exception {
        request(get("/api/resources/NAMESPACE/1/children"), 200);
        assertEquals(ResourceType.POD, repository.query.type());
        assertEquals(1L, repository.query.parentId());
    }

    @Test void invalidLimitAndStatusProduceProblemDetails() throws Exception {
        assertEquals("limit", request(get("/api/resources").param("limit", "101"), 400).get("field").asText());
        assertEquals("status", request(get("/api/resources").param("status", "unknown"), 400).get("field").asText());
    }

    @Test void invalidTypeParentAndCursorAre400() throws Exception {
        request(get("/api/resources").param("type", "invalid"), 400);
        request(get("/api/resources").param("parentId", "1"), 400);
        request(get("/api/resources").param("type", "POD").param("cursor", "!invalid"), 400);
    }

    @Test void missingAndNonNumericParametersAre400() throws Exception {
        request(get("/api/resources/search"), 400);
        request(get("/api/resources/search").param("q", " "), 400);
        request(get("/api/resources").param("limit", "abc"), 400);
        request(get("/api/resources/POD/abc"), 400);
        request(get("/api/resources/POD/0"), 400);
    }

    @Test void missingResourceProduces404() throws Exception {
        repository.found = Optional.empty();
        assertEquals("RESOURCE_NOT_FOUND", request(get("/api/resources/POD/9"), 404).get("code").asText());
        repository.present = false;
        request(get("/api/resources/NAMESPACE/9/children"), 404);
    }

    @Test void leafChildrenAreEmpty() throws Exception {
        assertEquals(0, request(get("/api/resources/POD/1/children"), 200).get("items").size());
    }

    @Test void timeoutResponseDoesNotExposeInternalExceptionMessage() {
        var response = new ResourceExceptionHandler().timeout(new QueryTimeoutException("private-sql-diagnostic"));
        assertEquals(503, response.getStatus());
        assertFalse(response.getDetail().contains("private-sql-diagnostic"));
        assertEquals("QUERY_TIMEOUT", response.getProperties().get("code"));
    }

    private JsonNode request(MockHttpServletRequestBuilder request, int expectedStatus) throws Exception {
        var response = mvc.perform(request).andReturn().getResponse();
        assertEquals(expectedStatus, response.getStatus(), response.getContentAsString());
        return mapper.readTree(response.getContentAsString());
    }
}
