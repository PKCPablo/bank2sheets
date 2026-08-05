package bank2sheets.http;

/** Transport-level failure of the shared {@link HttpExchange} seam (network or interruption). */
public class HttpExchangeException extends RuntimeException {

    public HttpExchangeException(String message) {
        super(message);
    }

    public HttpExchangeException(String message, Throwable cause) {
        super(message, cause);
    }
}
