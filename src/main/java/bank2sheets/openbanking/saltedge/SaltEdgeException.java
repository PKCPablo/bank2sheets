package bank2sheets.openbanking.saltedge;

/**
 * Raised when a Salt Edge request fails (non-success HTTP status, transport
 * error, or a required configuration value is missing).
 */
public class SaltEdgeException extends RuntimeException {

    public SaltEdgeException(String message) {
        super(message);
    }

    public SaltEdgeException(String message, Throwable cause) {
        super(message, cause);
    }
}
