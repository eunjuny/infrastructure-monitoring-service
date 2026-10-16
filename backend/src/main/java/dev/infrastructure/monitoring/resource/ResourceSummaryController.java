package dev.infrastructure.monitoring.resource;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/resources")
public class ResourceSummaryController {
    private final ResourceCountRepository repository;

    public ResourceSummaryController(ResourceCountRepository repository) {
        this.repository = repository;
    }

    @GetMapping("/summary")
    public ResourceSummary summary() {
        return repository.summary();
    }

    public record ResourceSummary(long clusters, long nodes, long namespaces, long pods) { }
}
