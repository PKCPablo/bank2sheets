package bank2sheets.http;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * In-memory HTTP stub keyed by path + query, recording every request. Shared
 * HTTP stub for the Sheets, Enable Banking, and Main command tests; the Sheets
 * tests pass {@code GOOGLE_BASE} and the Enable Banking tests pass their API
 * base ({@link #FakeHttp(String)}); no real network calls.
 */
public class FakeHttp implements HttpExchange {

    public final Map<String, Response> responses = new HashMap<>();
    public final List<Request> requests = new ArrayList<>();

    private final String base;

    /** URLs starting with the base are keyed by the remainder; others by full URL. */
    public FakeHttp(String base) {
        this.base = base;
    }

    public void stub(String pathAndQuery, String body) {
        responses.put(pathAndQuery, new Response(200, body));
    }

    public void stub(String pathAndQuery, int status, String body) {
        responses.put(pathAndQuery, new Response(status, body));
    }

    @Override
    public Response exchange(String method, String url, Map<String, String> headers, String body) {
        requests.add(new Request(method, url, headers, body));
        String key = url.startsWith(base) ? url.substring(base.length()) : url;
        Response response = responses.get(key);
        return response == null ? new Response(404,
                "{\"error\": {\"message\": \"no stub for %s\"}}".formatted(key)) : response;
    }

    public record Request(String method, String url, Map<String, String> headers, String body) {
    }
}
