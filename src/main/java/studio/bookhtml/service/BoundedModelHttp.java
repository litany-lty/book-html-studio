package studio.bookhtml.service;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import javax.net.ssl.SSLSession;

/** Deadline includes headers AND the full body. Byte limits are checked before copying buffers. */
final class BoundedModelHttp {
    private BoundedModelHttp() {}

    static HttpResponse<InputStream> send(HttpClient client, HttpRequest request, int maxBytes) throws Exception {
        if (maxBytes <= 0) throw new IllegalArgumentException("non-positive response limit");
        CompletableFuture<HttpResponse<byte[]>> future = client.sendAsync(request, info -> new Subscriber(maxBytes));
        HttpResponse<byte[]> response;
        try {
            long timeout = Math.max(1, request.timeout().orElse(Duration.ofSeconds(120)).toMillis());
            response = future.get(timeout, TimeUnit.MILLISECONDS);
        } catch (TimeoutException timeout) {
            future.cancel(true);
            throw new HttpTimeoutException("Model response deadline exceeded; remote outcome unknown");
        } catch (InterruptedException interrupted) {
            future.cancel(true);
            Thread.currentThread().interrupt();
            throw interrupted;
        } catch (ExecutionException failure) {
            future.cancel(true);
            throw new java.io.IOException("Model transport failed", failure.getCause());
        }
        InputStream body = new ByteArrayInputStream(response.body());
        return new HttpResponse<>() {
            public int statusCode() { return response.statusCode(); }
            public HttpRequest request() { return response.request(); }
            public Optional<HttpResponse<InputStream>> previousResponse() { return Optional.empty(); }
            public HttpHeaders headers() { return response.headers(); }
            public InputStream body() { return body; }
            public Optional<SSLSession> sslSession() { return response.sslSession(); }
            public URI uri() { return response.uri(); }
            public HttpClient.Version version() { return response.version(); }
        };
    }

    private static final class Subscriber implements HttpResponse.BodySubscriber<byte[]> {
        private final int limit;
        private final ByteArrayOutputStream output = new ByteArrayOutputStream();
        private final CompletableFuture<byte[]> result = new CompletableFuture<>();
        private Flow.Subscription subscription;
        Subscriber(int limit) { this.limit = limit; }
        public CompletionStage<byte[]> getBody() { return result; }
        public void onSubscribe(Flow.Subscription value) {
            if (subscription != null) { value.cancel(); return; }
            subscription = value;
            value.request(1);
        }
        public void onNext(List<ByteBuffer> buffers) {
            if (result.isDone()) return;
            for (ByteBuffer buffer : buffers) {
                int size = buffer.remaining();
                if ((long) output.size() + size > limit) {
                    subscription.cancel();
                    result.completeExceptionally(new java.io.IOException("Model response exceeds byte limit"));
                    return;
                }
                byte[] bytes = new byte[size];
                buffer.get(bytes);
                output.writeBytes(bytes);
            }
            subscription.request(1);
        }
        public void onError(Throwable failure) { result.completeExceptionally(failure); }
        public void onComplete() { result.complete(output.toByteArray()); }
    }
}
