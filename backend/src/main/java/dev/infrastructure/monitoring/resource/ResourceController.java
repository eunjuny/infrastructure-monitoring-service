package dev.infrastructure.monitoring.resource;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import static dev.infrastructure.monitoring.resource.ResourceResponses.*;

@RestController
@RequestMapping("/api/resources")
public class ResourceController {
    private final ResourceService service;

    public ResourceController(ResourceService service) { this.service = service; }

    @GetMapping
    public Page<Item> list(@RequestParam(defaultValue = "CLUSTER") String type,
                            @RequestParam(required = false) Long parentId,
                            @RequestParam(required = false) Long clusterId,
                            @RequestParam(required = false) Long namespaceId,
                            @RequestParam(defaultValue = "") String q,
                            @RequestParam(required = false) String status,
                            @RequestParam(defaultValue = "50") int limit,
                            @RequestParam(required = false) String cursor) {
        return service.list(ResourceQuery.list(type, parentId, q, status, limit, cursor, clusterId, namespaceId));
    }

    @GetMapping("/search")
    public Page<SearchResult> search(@RequestParam String q,
                                     @RequestParam(required = false) String type,
                                     @RequestParam(required = false) Long clusterId,
                                     @RequestParam(required = false) Long namespaceId,
                                     @RequestParam(required = false) String status,
                                     @RequestParam(defaultValue = "50") int limit,
                                     @RequestParam(required = false) String cursor) {
        return service.search(ResourceQuery.search(type, q, status, limit, cursor, clusterId, namespaceId));
    }

    @GetMapping("/{type}/{id}")
    public Detail detail(@PathVariable String type, @PathVariable long id) {
        return service.detail(ResourceType.parse(type), id);
    }

    @GetMapping("/{type}/{id}/children")
    public Page<Item> children(@PathVariable String type, @PathVariable long id,
                               @RequestParam(defaultValue = "") String q,
                               @RequestParam(required = false) String status,
                               @RequestParam(defaultValue = "50") int limit,
                               @RequestParam(required = false) String cursor) {
        return service.children(ResourceType.parse(type), id, q, status, limit, cursor);
    }

    @GetMapping("/{type}/{id}/path")
    public Path path(@PathVariable String type, @PathVariable long id) {
        return service.path(ResourceType.parse(type), id);
    }
}
