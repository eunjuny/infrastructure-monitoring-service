package dev.infrastructure.monitoring.history;

import dev.infrastructure.monitoring.resource.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/resources/{type}/{id}/events")
public class ResourceHistoryController {
    private final ResourceHistoryRepository history;
    private final ResourceService resources;
    private final int hours, maximum;
    public ResourceHistoryController(ResourceHistoryRepository history, ResourceService resources,
            @Value("${monitoring.history.retention-hours:24}") int hours, @Value("${monitoring.history.max-events:20000}") int maximum) {
        this.history=history; this.resources=resources; this.hours=hours; this.maximum=maximum;
    }
    @GetMapping public ResourceHistory.Page list(@PathVariable String type,@PathVariable long id,
            @RequestParam(defaultValue="20") int limit,@RequestParam(required=false) Long beforeId) {
        if (limit<1 || limit>100 || beforeId!=null && beforeId<=0) throw ResourceRequestException.invalid("events","이력 limit은 1–100, beforeId는 양수여야 합니다.");
        var resourceType=ResourceType.parse(type); resources.detail(resourceType,id);
        var items=history.list(resourceType,id,beforeId,limit+1); boolean more=items.size()>limit;
        var selected=items.subList(0,Math.min(limit,items.size()));
        return new ResourceHistory.Page(selected,more?selected.get(selected.size()-1).id():null,more,hours,maximum);
    }
}
