package bank2sheets;

/**
 * Base type for the CLI's expected failures (provider, Sheets, and transport
 * errors), so command handlers can catch them with a single clause and print
 * the message instead of a stack trace.
 */
public class Bank2SheetsException extends RuntimeException {

    public Bank2SheetsException(String message) {
        super(message);
    }

    public Bank2SheetsException(String message, Throwable cause) {
        super(message, cause);
    }
}
