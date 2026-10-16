package dev.infrastructure.monitoring.operations;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class OperationsController {
    private final PrometheusOperations operations;
    private final OperationsAlerts alerts;
    public OperationsController(PrometheusOperations operations, OperationsAlerts alerts) { this.operations=operations; this.alerts=alerts; }
    @GetMapping("/api/operations/metrics") public PrometheusOperations.Dashboard dashboard() { return operations.dashboard(); }
    @GetMapping("/api/operations/alerts") public OperationsAlerts.Snapshot alerts() { return alerts.snapshot(); }
}
