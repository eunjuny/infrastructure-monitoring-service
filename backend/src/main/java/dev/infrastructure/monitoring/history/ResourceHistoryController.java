package dev.infrastructure.monitoring.history;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/resources/{type}/{id}/events")
public class ResourceHistoryController {
    private final ResourceHistoryService history;

    public ResourceHistoryController(ResourceHistoryService history) {
        this.history = history;
    }

    @GetMapping
    public ResourceHistory.Page list(@PathVariable String type, @PathVariable long id,
            @RequestParam(defaultValue = "20") int limit, @RequestParam(required = false) Long beforeId) {
        return history.list(type, id, limit, beforeId);
    }
}
