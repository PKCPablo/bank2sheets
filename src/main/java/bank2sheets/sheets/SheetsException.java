package bank2sheets.sheets;

/**
 * Raised when a Google Sheets request fails (non-success HTTP status, transport
 * error, or a required configuration value is missing).
 *
 * <p>ponytail: same shape as {@link bank2sheets.openbanking.saltedge.SaltEdgeException};
 * share a common exception if a third client appears.
 */
public class SheetsException extends RuntimeException {

    public SheetsException(String message) {
        super(message);
    }

    public SheetsException(String message, Throwable cause) {
        super(message, cause);
    }
}
