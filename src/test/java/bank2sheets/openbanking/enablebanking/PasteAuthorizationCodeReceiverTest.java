package bank2sheets.openbanking.enablebanking;

import bank2sheets.Bank2SheetsException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** Tests for the paste-based callback receiver used with HTTPS redirect URLs. */
class PasteAuthorizationCodeReceiverTest {

    @Test
    void returnsTrimmedPastedUrl() {
        PasteAuthorizationCodeReceiver receiver = new PasteAuthorizationCodeReceiver(
                () -> "  https://pkcpablo.github.io/bank2sheets/callback.html?code=abc&state=xyz  \n");
        assertEquals("https://pkcpablo.github.io/bank2sheets/callback.html?code=abc&state=xyz",
                receiver.awaitCallbackUrl());
    }

    @Test
    void blankInputThrows() {
        PasteAuthorizationCodeReceiver receiver = new PasteAuthorizationCodeReceiver(() -> "   ");
        Bank2SheetsException error = assertThrows(Bank2SheetsException.class, receiver::awaitCallbackUrl);
        assertTrue(error.getMessage().contains("blank"));
    }

    @Test
    void nullInputThrows() {
        PasteAuthorizationCodeReceiver receiver = new PasteAuthorizationCodeReceiver(() -> null);
        assertThrows(Bank2SheetsException.class, receiver::awaitCallbackUrl);
    }
}
