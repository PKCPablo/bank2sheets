package bank2sheets.sheets;

import bank2sheets.Bank2SheetsException;

/**
 * Raised when a Google Sheets request fails (non-success HTTP status, transport
 * error, or a required configuration value is missing).
 */
public class SheetsException extends Bank2SheetsException {

    public SheetsException(String message) {
        super(message);
    }

    public SheetsException(String message, Throwable cause) {
        super(message, cause);
    }
}
