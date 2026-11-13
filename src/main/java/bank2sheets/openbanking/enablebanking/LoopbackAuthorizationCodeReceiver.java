package bank2sheets.openbanking.enablebanking;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Captures the OAuth callback with a JDK loopback HTTP server bound to
 * {@code 127.0.0.1} on the redirect URL's port and path. Responds with a small
 * HTML confirmation page and returns the full callback URL (including
 * {@code code} and {@code state}) to the caller.
 *
 * <p>{@link #start()} binds the listener so the port is open before the
 * authorization URL is opened in the browser; {@link #awaitCallbackUrl()} calls
 * it when needed, so both orders work. Times out (default 5 minutes) with a
 * clear error so a CLI run never hangs forever when the user abandons the SCA
 * flow.
 */
public final class LoopbackAuthorizationCodeReceiver implements AuthorizationCodeReceiver {

    private static final long DEFAULT_TIMEOUT_SECONDS = 300;

    private final URI redirectUrl;
    private final long timeoutSeconds;
    private volatile int boundPort = -1;
    private HttpServer server;
    private CompletableFuture<String> callbackUrl;

    public LoopbackAuthorizationCodeReceiver(URI redirectUrl) {
        this(redirectUrl, DEFAULT_TIMEOUT_SECONDS);
    }

    public LoopbackAuthorizationCodeReceiver(URI redirectUrl, long timeoutSeconds) {
        this.redirectUrl = redirectUrl;
        this.timeoutSeconds = timeoutSeconds;
    }

    /** The port the listener actually bound to (useful when the redirect URL uses port 0); -1 before binding. */
    public int boundPort() {
        return boundPort;
    }

    /** Binds and starts the loopback listener; idempotent (a second call does nothing). */
    @Override
    public synchronized void start() {
        if (server != null) {
            return;
        }
        CompletableFuture<String> future = new CompletableFuture<>();
        HttpServer created;
        try {
            created = HttpServer.create(new InetSocketAddress("127.0.0.1", redirectUrl.getPort()), 0);
        } catch (IOException | IllegalArgumentException e) {
            // IllegalArgumentException: invalid port (e.g. -1 when omitted) or bind failure.
            throw new EnableBankingException(
                    "Cannot bind loopback callback listener on 127.0.0.1:" + redirectUrl.getPort()
                            + redirectUrl.getPath(), e);
        }
        created.createContext(redirectUrl.getPath(), exchange -> {
            try {
                String url = "http://127.0.0.1:" + exchange.getLocalAddress().getPort()
                        + exchange.getRequestURI().toString();
                respond(exchange);
                future.complete(url);
            } catch (RuntimeException e) {
                future.completeExceptionally(e);
            }
        });
        created.start();
        server = created;
        callbackUrl = future;
        boundPort = created.getAddress().getPort();
    }

    @Override
    public String awaitCallbackUrl() {
        start();
        CompletableFuture<String> future = callbackUrl;
        try {
            return future.get(timeoutSeconds, TimeUnit.SECONDS);
        } catch (TimeoutException e) {
            throw new EnableBankingException(
                    "Timed out after " + timeoutSeconds + " s waiting for the authorization callback on "
                            + "http://127.0.0.1:" + boundPort + redirectUrl.getPath()
                            + "; complete the SCA flow in the bank app and retry");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new EnableBankingException("Interrupted while waiting for the authorization callback", e);
        } catch (ExecutionException e) {
            throw new EnableBankingException("Failed to capture the authorization callback", e.getCause());
        } finally {
            stop();
        }
    }

    private synchronized void stop() {
        if (server != null) {
            server.stop(0);
            server = null;
            callbackUrl = null;
            boundPort = -1;
        }
    }

    private static void respond(HttpExchange exchange) throws IOException {
        byte[] body = ("<html><body><h2>bank2sheets</h2><p>Authorization received; "
                + "you can close this window and return to the terminal.</p></body></html>")
                .getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "text/html; charset=utf-8");
        exchange.sendResponseHeaders(200, body.length);
        exchange.getResponseBody().write(body);
        exchange.close();
    }
}
