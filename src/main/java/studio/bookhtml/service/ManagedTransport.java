package studio.bookhtml.service;

import java.io.IOException;
import java.net.http.HttpRequest;
import java.util.function.BooleanSupplier;

/**
 * Shared interface for managed HTTP transport with monotonic deadline,
 * bounded body size, and cancellation check.
 */
@FunctionalInterface
public interface ManagedTransport {
    BoundedHttp.Response send(HttpRequest request, long deadlineNanos, int maxBytes, BooleanSupplier cancelled)
            throws IOException;
}
