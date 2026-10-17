package dev.infrastructure.monitoring.operations;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.time.Duration;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.stereotype.Service;

/** ADMIN-only recipient control; SMTP secrets and configuration stay outside the application. */
@Service
@ConditionalOnProperty(name = "monitoring.auth.enabled", havingValue = "true")
public class AlertEmailService {
    public record Settings(String recipient, boolean enabled) {}
    public record Snapshot(String recipient, boolean enabled, boolean smtpConfigured, boolean available) {}
    private static final Pattern ADDRESS = Pattern.compile("[A-Za-z0-9.!#$%&'*+/=?^_`{|}~-]+@[A-Za-z0-9](?:[A-Za-z0-9.-]*[A-Za-z0-9])?\\.[A-Za-z]{2,63}");
    private final ObjectMapper mapper;
    private final String token;
    private final URI endpoint;
    private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2))
            .followRedirects(HttpClient.Redirect.NEVER).build();

    public AlertEmailService(ObjectMapper mapper,
            @Value("${ALERT_EMAIL_URL:http://alert-receiver:8090}") String base,
            @Value("${ALERT_EMAIL_CONTROL_TOKEN:}") String token) {
        var uri = URI.create(base);
        if (!"http".equals(uri.getScheme()) || !Set.of("alert-receiver", "127.0.0.1", "localhost").contains(uri.getHost())
                || uri.getUserInfo() != null || uri.getQuery() != null || uri.getFragment() != null
                || !Set.of("", "/").contains(uri.getPath())) {
            throw new IllegalArgumentException("Use a fixed internal notification endpoint");
        }
        this.mapper = mapper;
        this.token = token;
        this.endpoint = URI.create(base.replaceAll("/$", "") + "/email-settings");
    }

    public Snapshot read() {
        return exchange(null);
    }

    public Snapshot update(Settings settings) {
        var address = settings.recipient();
        if (address == null || address.length() > 254 || address.contains("..")
                || (!address.isEmpty() && (!ADDRESS.matcher(address).matches() || address.indexOf('@') > 64))
                || (settings.enabled() && address.isEmpty())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Enter one plain email address");
        }
        return exchange(settings);
    }

    private Snapshot exchange(Settings settings) {
        if (token.length() < 32) throw unavailable();
        try {
            var request = HttpRequest.newBuilder(endpoint).timeout(Duration.ofSeconds(3))
                    .header("Authorization", "Bearer " + token).header("Accept", "application/json");
            if (settings == null) request.GET();
            else request.header("Content-Type", "application/json").PUT(HttpRequest.BodyPublishers.ofByteArray(mapper.writeValueAsBytes(settings)));
            var future = client.sendAsync(request.build(), ignored -> new PrometheusClient.LimitedBody());
            try {
                var response = future.get(3, TimeUnit.SECONDS);
                if (response.statusCode() == 400) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Check recipient and SMTP setup");
                if (response.statusCode() != 200) throw unavailable();
                return mapper.readValue(response.body(), Snapshot.class);
            } finally {
                if (!future.isDone()) future.cancel(true);
            }
        } catch (ResponseStatusException cause) {
            throw cause;
        } catch (Exception cause) {
            if (cause instanceof InterruptedException) Thread.currentThread().interrupt();
            throw unavailable();
        }
    }

    private static ResponseStatusException unavailable() {
        return new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Notification configuration unavailable");
    }
}
