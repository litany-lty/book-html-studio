package studio.bookhtml.service;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import java.net.*;
import java.net.http.*;
import java.time.Duration;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

class ModelBodyDeadlineAuditTest {
    @Test void deadlineIncludesStalledBodyAfterHeaders() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        CountDownLatch release = new CountDownLatch(1), headers = new CountDownLatch(1);
        server.createContext("/", exchange -> {
            try { exchange.sendResponseHeaders(200, 20); exchange.getResponseBody().write('x'); exchange.getResponseBody().flush(); headers.countDown(); release.await(4, TimeUnit.SECONDS); }
            catch (Exception ignored) {} finally { exchange.close(); }
        });
        server.start();
        try {
            var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + server.getAddress().getPort())).timeout(Duration.ofSeconds(1)).build();
            assertThrows(HttpTimeoutException.class, () -> BoundedModelHttp.send(HttpClient.newHttpClient(), request, 100));
            assertEquals(0, headers.getCount(), "the body, not response headers, is stalled");
        } finally { release.countDown(); server.stop(0); }
    }
    @Test void oversizedBodyFailsBeforeReturningBytes() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> { try { byte[] bytes = new byte[1024]; exchange.sendResponseHeaders(200, bytes.length); exchange.getResponseBody().write(bytes); } finally { exchange.close(); } });
        server.start();
        try {
            var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + server.getAddress().getPort())).timeout(Duration.ofSeconds(2)).build();
            assertThrows(java.io.IOException.class, () -> BoundedModelHttp.send(HttpClient.newHttpClient(), request, 128));
        } finally { server.stop(0); }
    }
}
