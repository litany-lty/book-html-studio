package studio.bookhtml.service;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;

/**
 * F04：项目级共享有界传输。单 HttpClient 实例（连接超时固定、不跟随重定向，
 * 跨域绝不携带凭证）、有界线程池（不用 commonPool）；单调时钟总 deadline 覆盖
 * 响应头到达 + 完整响应体读取 + 最大字节；取消同时关闭 body、取消 HTTP future。
 * 请求头到达不等于请求完成；“每次读取成功就重置总时限”在此明确禁止。
 */
public final class BoundedHttp implements AutoCloseable {
    public enum Kind { TIMEOUT, CANCELLED, TOO_LARGE, IO }

    public static final class BoundedHttpException extends IOException {
        private final Kind kind;
        public BoundedHttpException(Kind kind, String message) { super(message); this.kind = kind; }
        public BoundedHttpException(Kind kind, String message, Throwable cause) { super(message, cause); this.kind = kind; }
        public Kind kind() { return kind; }
    }

    public record Response(int status, byte[] body) {
        public Response {
            body = body == null ? new byte[0] : body.clone();
        }
    }

    private static final AtomicInteger POOL_SEQ = new AtomicInteger();

    private final HttpClient client;
    private final ThreadPoolExecutor workers;
    private final int maxThreads;

    public BoundedHttp(int maxThreads, Duration connectTimeout) {
        if (maxThreads < 1) throw new IllegalArgumentException("线程数非法");
        this.maxThreads = maxThreads;
        this.workers = new ThreadPoolExecutor(maxThreads, maxThreads, 60, TimeUnit.SECONDS,
                new ArrayBlockingQueue<>(maxThreads * 4), r -> {
                    Thread t = new Thread(r, "bounded-http-" + POOL_SEQ.incrementAndGet());
                    t.setDaemon(true);
                    return t;
                }, new ThreadPoolExecutor.AbortPolicy());
        this.client = HttpClient.newBuilder()
                .connectTimeout(connectTimeout)
                .followRedirects(HttpClient.Redirect.NEVER)
                .executor(workers)
                .build();
    }

    public int maxThreads() { return maxThreads; }

    public int activeThreads() { return workers.getActiveCount(); }

    public Response send(HttpRequest request, long deadlineNanos, int maxBytes, BooleanSupplier cancelled)
            throws BoundedHttpException {
        long deadline = System.nanoTime() + deadlineNanos;
        CompletableFuture<HttpResponse<InputStream>> future =
                client.sendAsync(request, HttpResponse.BodyHandlers.ofInputStream());
        HttpResponse<InputStream> response = awaitFuture(future, null, deadline, cancelled, "响应头等待超时");
        int status = response.statusCode();
        InputStream stream = response.body();
        if (stream == null) return new Response(status, new byte[0]);
        CompletableFuture<byte[]> bodyFuture = CompletableFuture.supplyAsync(
                () -> readBounded(stream, maxBytes), workers);
        try {
            return new Response(status, awaitFuture(bodyFuture, stream, deadline, cancelled, "响应体读取超时"));
        } finally {
            closeQuietly(stream);
        }
    }

    private static byte[] readBounded(InputStream stream, int maxBytes) {
        try (InputStream in = stream; ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192];
            int total = 0, read;
            while ((read = in.read(buffer)) != -1) {
                total += read;
                if (total > maxBytes) throw new CompletionException(
                        new BoundedHttpException(Kind.TOO_LARGE, "响应超过最大字节限制"));
                out.write(buffer, 0, read);
            }
            return out.toByteArray();
        } catch (IOException e) {
            throw new CompletionException(e);
        }
    }

    private <T> T awaitFuture(CompletableFuture<T> future, InputStream stream, long deadline,
                              BooleanSupplier cancelled, String timeoutMessage) throws BoundedHttpException {
        while (true) {
            if (cancelled.getAsBoolean()) {
                future.cancel(true);
                closeQuietly(stream);
                throw new BoundedHttpException(Kind.CANCELLED, "请求已取消");
            }
            long remaining = deadline - System.nanoTime();
            if (remaining <= 0) {
                future.cancel(true);
                closeQuietly(stream);
                throw new BoundedHttpException(Kind.TIMEOUT, timeoutMessage);
            }
            try {
                return future.get(Math.min(TimeUnit.NANOSECONDS.toMillis(remaining) + 1, 200),
                        TimeUnit.MILLISECONDS);
            } catch (TimeoutException ignored) {
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                future.cancel(true);
                closeQuietly(stream);
                throw new BoundedHttpException(Kind.CANCELLED, "等待被中断", e);
            } catch (CancellationException e) {
                closeQuietly(stream);
                throw new BoundedHttpException(Kind.CANCELLED, "请求已取消", e);
            } catch (ExecutionException e) {
                closeQuietly(stream);
                Throwable cause = e.getCause();
                while (cause instanceof CompletionException && cause.getCause() != null) cause = cause.getCause();
                if (cause instanceof BoundedHttpException bounded) throw bounded;
                throw new BoundedHttpException(Kind.IO, "网络读写失败", cause);
            }
        }
    }

    private static void closeQuietly(InputStream stream) {
        if (stream == null) return;
        try { stream.close(); } catch (IOException ignored) {}
    }

    @Override
    public void close() {
        workers.shutdownNow();
    }
}
