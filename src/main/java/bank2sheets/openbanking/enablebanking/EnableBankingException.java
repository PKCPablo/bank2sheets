package bank2sheets.openbanking.enablebanking;

import bank2sheets.Bank2SheetsException;

/**
 * Raised when an Enable Banking request fails (non-success HTTP status,
 * transport error, or a required configuration value is missing). The message
 * includes the HTTP status, method, path, and response body when available.
 */
public class EnableBankingException extends Bank2SheetsException {

    public EnableBankingException(String message) {
        super(message);
    }

    public EnableBankingException(String message, Throwable cause) {
        super(message, cause);
    }
}
