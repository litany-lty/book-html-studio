package studio.bookhtml.decision;

import java.io.IOException;
import java.net.http.HttpRequest;
import java.util.function.BooleanSupplier;
import studio.bookhtml.service.BoundedHttp;

/** J04：决策传输抽象。首版只实现 TypeSafe 适配；不做多渠道自动切换与 fallback。 */
public interface DecisionTransport {
    BoundedHttp.Response send(HttpRequest request, long deadlineNanos, int maxBytes,
                              BooleanSupplier cancelled) throws IOException;
}
