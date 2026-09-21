package studio.bookhtml.decision;

import java.io.IOException;
import java.net.http.HttpRequest;
import java.time.Duration;
import java.util.function.BooleanSupplier;
import studio.bookhtml.service.BoundedHttp;

/**
 * F04/J04：共享有界传输的决策侧适配。单例复用，不为每次请求新建 client；
 * 不隐式重试、不跨渠道 fallback、不修 JSON。
 */
public class SharedTransport implements DecisionTransport, AutoCloseable {
    private final BoundedHttp http;

    public SharedTransport() {
        this.http = new BoundedHttp(4, Duration.ofSeconds(10));
    }

    SharedTransport(BoundedHttp http) {
        this.http = http;
    }

    @Override
    public BoundedHttp.Response send(HttpRequest request, long deadlineNanos, int maxBytes,
                                     BooleanSupplier cancelled) throws IOException {
        return http.send(request, deadlineNanos, maxBytes, cancelled);
    }

    @Override
    public void close() {
        http.close();
    }
}
