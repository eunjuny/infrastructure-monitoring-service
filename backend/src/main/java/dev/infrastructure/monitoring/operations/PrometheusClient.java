package dev.infrastructure.monitoring.operations;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;

/** Fixed internal Prometheus only; shared deadline/redirect/body limits for metrics and rules. */
final class PrometheusClient {
    private final String base;
    private final ObjectMapper mapper;
    private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2))
            .followRedirects(HttpClient.Redirect.NEVER).build();

    PrometheusClient(String base, ObjectMapper mapper) {
        var uri = URI.create(base);
        if (!"http".equals(uri.getScheme()) || uri.getUserInfo() != null || uri.getQuery() != null
                || uri.getFragment() != null || (uri.getPath() != null && !Set.of("", "/").contains(uri.getPath()))
                || !Set.of("127.0.0.1", "localhost", "prometheus").contains(uri.getHost())) {
            throw new IllegalArgumentException("Prometheus must use a configured local/internal endpoint");
        }
        this.base = base.replaceAll("/$", "");
        this.mapper = mapper;
    }

    JsonNode data(String path) throws Exception {
        if (!path.startsWith("/api/v1/")) throw new IllegalArgumentException("Unsupported monitoring endpoint");
        var request = HttpRequest.newBuilder(URI.create(base + path)).timeout(Duration.ofSeconds(3)).GET().build();
        var future = client.sendAsync(request, information -> new LimitedBody());
        try {
            var response = future.get(3, TimeUnit.SECONDS);
            if (response.statusCode() != 200) throw new IllegalStateException("Prometheus response rejected");
            var body = mapper.readTree(response.body());
            if (!"success".equals(body.path("status").asText())) throw new IllegalStateException("Prometheus query failed");
            return body.path("data");
        } finally {
            if (!future.isDone()) future.cancel(true);
        }
    }

    static final class LimitedBody implements HttpResponse.BodySubscriber<byte[]> {
        private final CompletableFuture<byte[]> result = new CompletableFuture<>();
        private final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        private Flow.Subscription subscription;
        public CompletionStage<byte[]> getBody() { return result; }
        public void onSubscribe(Flow.Subscription value) { subscription = value; value.request(1); }
        public void onNext(List<ByteBuffer> buffers) {
            for (var buffer : buffers) {
                if (bytes.size() + buffer.remaining() > 1048576) {
                    subscription.cancel();
                    result.completeExceptionally(new IllegalStateException("Prometheus response too large"));
                    return;
                }
                byte[] chunk = new byte[buffer.remaining()];
                buffer.get(chunk);
                bytes.writeBytes(chunk);
            }
            subscription.request(1);
        }
        public void onError(Throwable cause) { result.completeExceptionally(cause); }
        public void onComplete() { result.complete(bytes.toByteArray()); }
    }
}
