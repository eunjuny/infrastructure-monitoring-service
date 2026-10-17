package dev.infrastructure.monitoring.operations;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.http.ResponseEntity;
import org.springframework.web.server.ResponseStatusException;
import java.util.Map;

@RestController
@ConditionalOnProperty(name = "monitoring.auth.enabled", havingValue = "true")
public class AlertEmailController {
    private final AlertEmailService service;

    public AlertEmailController(AlertEmailService service) { this.service = service; }

    @GetMapping("/api/operations/alert-email")
    public AlertEmailService.Snapshot read() { return service.read(); }

    @PutMapping("/api/operations/alert-email")
    public AlertEmailService.Snapshot update(@RequestBody AlertEmailService.Settings settings) {
        return service.update(settings);
    }

    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<Map<String, String>> failure(ResponseStatusException cause) {
        // Handle here: a denied /error dispatch must not turn a validation 400 into a misleading 403.
        return ResponseEntity.status(cause.getStatusCode()).body(Map.of("message",
                cause.getStatusCode().value() == 400 ? "수신 주소와 SMTP 설정을 확인해 주세요." : "알림 설정을 적용할 수 없습니다."));
    }
}
