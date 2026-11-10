package bank2sheets.openbanking.saltedge;

import bank2sheets.Bank2SheetsException;

/**
 * Raised when a Salt Edge request fails (non-success HTTP status, transport
 * error, or a required configuration value is missing).
 */
public class SaltEdgeException extends Bank2SheetsException {

    public SaltEdgeException(String message) {
        super(message);
    }
}
