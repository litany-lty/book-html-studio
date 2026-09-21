package studio.bookhtml.service;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpRequest;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * F04（T15/T16/T42 传输侧）：总 deadline 覆盖头与体、最大字节、取消真关闭、
 * 不跟随跨域重定向；线程有界。
 */
class BoundedHttpTest {
    private HttpServer server;
    private HttpServer other;
    private final AtomicInteger otherHits = new AtomicInteger();

    @AfterEach void stop() {
        if (server != null) server.stop(0);
        if (other != null) other.stop(0);
    }

    private String startDefault() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.setExecutor(Executors.newCachedThreadPool(r -> {
            Thread t = new Thread(r);
            t.setDaemon(true);
            return t;
        }));
        server.start();
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    @Test void totalDeadlineCoversSlowHeadersAndSlowBody() throws Exception {
        String base = startDefault();
        server.createContext("/slow-headers", exchange -> {
            try { Thread.sleep(2000); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            byte[] body = "late".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            try (OutputStream out = exchange.getResponseBody()) { out.write(body); }
        });
        server.createContext("/slow-body", exchange -> {
            exchange.sendResponseHeaders(200, 0);
            try (OutputStream out = exchange.getResponseBody()) {
                for (int i = 0; i < 40; i++) {
                    Thread.sleep(100);
                    out.write("chunk".getBytes(StandardCharsets.UTF_8));
                    out.flush();
                }
            } catch (IOException | InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
        try (BoundedHttp http = new BoundedHttp(4, Duration.ofSeconds(5))) {
            HttpRequest slowHeaders = HttpRequest.newBuilder(URI.create(base + "/slow-headers")).GET().build();
            BoundedHttp.BoundedHttpException e1 = assertThrows(BoundedHttp.BoundedHttpException.class,
                    () -> http.send(slowHeaders, Duration.ofMillis(500).toNanos(), 65536, () -> false));
            assertEquals(BoundedHttp.Kind.TIMEOUT, e1.kind());

            // 持续慢速响应体：总时限不因“每次读取成功”而重置，必须超时
            HttpRequest slowBody = HttpRequest.newBuilder(URI.create(base + "/slow-body")).GET().build();
            long start = System.nanoTime();
            BoundedHttp.BoundedHttpException e2 = assertThrows(BoundedHttp.BoundedHttpException.class,
                    () -> http.send(slowBody, Duration.ofMillis(800).toNanos(), 65536, () -> false));
            long elapsed = System.nanoTime() - start;
            assertEquals(BoundedHttp.Kind.TIMEOUT, e2.kind());
            assertTrue(elapsed < Duration.ofSeconds(5).toNanos(), "总 deadline 必须生效，实际耗时纳秒=" + elapsed);
            // 有界线程释放
            long deadline = System.currentTimeMillis() + 3000;
            while (http.activeThreads() > 0 && System.currentTimeMillis() < deadline) Thread.sleep(20);
            assertEquals(0, http.activeThreads());
        }
    }

    @Test void maxBytesRedirectAndCancel() throws Exception {
        String base = startDefault();
        other = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        other.createContext("/", exchange -> {
            otherHits.incrementAndGet();
            byte[] body = "other".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            try (OutputStream out = exchange.getResponseBody()) { out.write(body); }
        });
        other.setExecutor(Executors.newCachedThreadPool(r -> {
            Thread t = new Thread(r);
            t.setDaemon(true);
            return t;
        }));
        other.start();
        String otherBase = "http://127.0.0.1:" + other.getAddress().getPort();
        server.createContext("/big", exchange -> {
            byte[] body = new byte[200000];
            exchange.sendResponseHeaders(200, body.length);
            try (OutputStream out = exchange.getResponseBody()) { out.write(body); }
        });
        server.createContext("/redirect", exchange -> {
            exchange.getResponseHeaders().add("Location", otherBase + "/");
            exchange.sendResponseHeaders(302, -1);
            exchange.close();
        });
        server.createContext("/never", exchange -> {
            try { Thread.sleep(10000); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        });
        try (BoundedHttp http = new BoundedHttp(4, Duration.ofSeconds(5))) {
            HttpRequest big = HttpRequest.newBuilder(URI.create(base + "/big")).GET().build();
            BoundedHttp.BoundedHttpException tooLarge = assertThrows(BoundedHttp.BoundedHttpException.class,
                    () -> http.send(big, Duration.ofSeconds(10).toNanos(), 65536, () -> false));
            assertEquals(BoundedHttp.Kind.TOO_LARGE, tooLarge.kind());

            // 跨域重定向不跟随、不带凭证外发
            HttpRequest redirect = HttpRequest.newBuilder(URI.create(base + "/redirect"))
                    .header("Authorization", "Bearer SECRET").GET().build();
            BoundedHttp.Response r = http.send(redirect, Duration.ofSeconds(10).toNanos(), 65536, () -> false);
            assertEquals(302, r.status());
            assertEquals(0, otherHits.get());

            // 取消关闭真实 IO
            HttpRequest never = HttpRequest.newBuilder(URI.create(base + "/never")).GET().build();
            BooleanSupplier cancelled = () -> true;
            BoundedHttp.BoundedHttpException e = assertThrows(BoundedHttp.BoundedHttpException.class,
                    () -> http.send(never, Duration.ofSeconds(10).toNanos(), 65536, cancelled));
            assertEquals(BoundedHttp.Kind.CANCELLED, e.kind());
        }
    }
}
