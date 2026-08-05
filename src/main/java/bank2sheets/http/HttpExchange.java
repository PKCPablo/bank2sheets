package bank2sheets.http;

import java.util.Map;

/**
 * Minimal shared HTTP seam for bank2sheets clients.
 *
 * <p>The real implementation ({@link RealHttpExchange}) wraps
 * {@link java.net.http.HttpClient}; tests inject an in-memory fake.
 * {@code java.net.http.HttpClient} is a concrete class, so this tiny functional
 * interface is the cheapest way to make clients testable without adding a
 * heavier HTTP-stubbing framework.
 */
@FunctionalInterface
public interface HttpExchange {

    Response exchange(String method, String url, Map<String, String> headers, String body);

    record Response(int status, String body) {
        public boolean ok() {
            return status >= 200 && status < 300;
        }
    }
}
