package bank2sheets.http;

import bank2sheets.Bank2SheetsException;

/** Transport-level failure of the shared {@link HttpExchange} seam (network or interruption). */
public class HttpExchangeException extends Bank2SheetsException {

    public HttpExchangeException(String message, Throwable cause) {
        super(message, cause);
    }
}
