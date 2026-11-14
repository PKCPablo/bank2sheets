package bank2sheets.openbanking.enablebanking;

import bank2sheets.Bank2SheetsException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.Test;

/** LoopbackAuthorizationCodeReceiver tests with a real local HTTP request; no external network. */
class LoopbackAuthorizationCodeReceiverTest {

    @Test
    void capturesCallbackUrlOnEphemeralPort() throws Exception {
        LoopbackAuthorizationCodeReceiver receiver = new LoopbackAuthorizationCodeReceiver(
                URI.create("http://127.0.0.1:0/bank2sheets/callback"), 60);
        ExecutorService executor = Executors.newSingleThreadExecutor();
        Future<String> callback = executor.submit(receiver::awaitCallbackUrl);
        try {
            int port = boundPort(receiver);
            String callbackUrl = "http://127.0.0.1:" + port + "/bank2sheets/callback?code=abc&state=s1";

            HttpResponse<String> response = HttpClient.newHttpClient().send(
                    HttpRequest.newBuilder(URI.create(callbackUrl)).build(),
                    HttpResponse.BodyHandlers.ofString());

            assertEquals(200, response.statusCode());
            assertTrue(response.body().contains("bank2sheets"));
            assertEquals(callbackUrl, callback.get(5, java.util.concurrent.TimeUnit.SECONDS));
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void startIsIdempotentAndAwaitReturnsCallback() throws Exception {
        LoopbackAuthorizationCodeReceiver receiver = new LoopbackAuthorizationCodeReceiver(
                URI.create("http://127.0.0.1:0/bank2sheets/callback"), 60);
        receiver.start();
        receiver.start(); // second bind must be a no-op, not an "address in use" failure
        ExecutorService executor = Executors.newSingleThreadExecutor();
        Future<String> callback = executor.submit(receiver::awaitCallbackUrl);
        try {
            int port = receiver.boundPort();
            String callbackUrl = "http://127.0.0.1:" + port + "/bank2sheets/callback?code=abc&state=s1";

            HttpResponse<String> response = HttpClient.newHttpClient().send(
                    HttpRequest.newBuilder(URI.create(callbackUrl)).build(),
                    HttpResponse.BodyHandlers.ofString());

            assertEquals(200, response.statusCode());
            assertEquals(callbackUrl, callback.get(5, java.util.concurrent.TimeUnit.SECONDS));
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void timesOutWithClearErrorWhenNoCallbackArrives() {
        LoopbackAuthorizationCodeReceiver receiver = new LoopbackAuthorizationCodeReceiver(
                URI.create("http://127.0.0.1:0/bank2sheets/callback"), 1);

        Bank2SheetsException error = assertThrows(Bank2SheetsException.class,
                receiver::awaitCallbackUrl);

        assertTrue(error.getMessage().contains("Timed out after 1 s"));
        assertTrue(error.getMessage().contains("/bank2sheets/callback"));
    }

    /** Waits until the listener binds its ephemeral port. */
    private static int boundPort(LoopbackAuthorizationCodeReceiver receiver) throws InterruptedException {
        for (int i = 0; i < 100 && receiver.boundPort() < 0; i++) {
            Thread.sleep(10);
        }
        assertTrue(receiver.boundPort() > 0, "listener did not bind in time");
        return receiver.boundPort();
    }
}
