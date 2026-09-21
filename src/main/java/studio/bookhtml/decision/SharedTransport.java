package studio.bookhtml.decision;

import java.io.IOException;
import java.net.http.HttpRequest;
import java.time.Duration;
import java.util.function.BooleanSupplier;
import org.springframework.stereotype.Service;
import studio.bookhtml.service.BoundedHttp;

/**
 * F04/J04：共享有界传输的决策侧适配。单例复用，不为每次请求新建 client；
 * 不隐式重试、不跨渠道 fallback、不修 JSON。
 */
@Service
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
        try {
            return http.send(request, deadlineNanos, maxBytes, cancelled);
        } catch (BoundedHttp.BoundedHttpException e) {
            throw e;
        } catch (IOException e) {
            throw new IOException("IO:有界传输失败", e);
        }
    }

    @Override
    public void close() {
        http.close();
    }
}
