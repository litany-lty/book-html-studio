package studio.bookhtml.service;

import java.net.http.HttpRequest;

/**
 * Functional factory for constructing actual HTTP requests with secrets only after
 * resources and budget reservations have been granted.
 */
@FunctionalInterface
public interface RequestFactory {
    HttpRequest buildRequest() throws Exception;
}
