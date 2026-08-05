package bank2sheets.http;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Map;

/** Single shared real HTTP transport over {@link java.net.http.HttpClient}. */
public final class RealHttpExchange implements HttpExchange {

    private final HttpClient client = HttpClient.newHttpClient();

    @Override
    public Response exchange(String method, String url, Map<String, String> headers, String body) {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(url))
                .method(method, body == null ? HttpRequest.BodyPublishers.noBody()
                        : HttpRequest.BodyPublishers.ofString(body));
        headers.forEach(builder::header);
        try {
            HttpResponse<String> response = client.send(builder.build(), HttpResponse.BodyHandlers.ofString());
            return new Response(response.statusCode(), response.body());
        } catch (IOException | InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new HttpExchangeException("HTTP request failed: " + method + " " + url, e);
        }
    }
}
