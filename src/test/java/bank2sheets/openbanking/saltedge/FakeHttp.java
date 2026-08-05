package bank2sheets.openbanking.saltedge;

import bank2sheets.http.HttpExchange;
import bank2sheets.http.HttpExchange.Response;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * In-memory HTTP stub keyed by path + query, recording every request. Shared by
 * SaltEdgeClient and Main command tests; the sheets tests reuse it through a
 * base-URL override ({@link #FakeHttp(String)}); no real network calls.
 *
 * <p>ponytail: this fake serves the shared {@code bank2sheets.http} seam but
 * stays in the {@code openbanking.saltedge} test package (its historical home)
 * so both client test suites and Main command tests keep importing it from one
 * stable place; moving it would churn every test file for no behavior change.
 */
public class FakeHttp implements HttpExchange {

    public static final String BASE = "https://www.saltedge.com/api/v5";

    public final Map<String, Response> responses = new HashMap<>();
    public final List<Request> requests = new ArrayList<>();

    private final String base;

    public FakeHttp() {
        this(BASE);
    }

    /** Override for a non-Salt-Edge base; URLs not starting with the base are keyed by full URL. */
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
