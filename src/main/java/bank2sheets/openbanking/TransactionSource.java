package bank2sheets.openbanking;

import bank2sheets.model.Account;
import bank2sheets.model.Transaction;
import java.util.List;

/**
 * Read-only (AIS) source of bank transactions for one provider.
 *
 * <p>Security boundary: implementations only create read consents and never
 * create payment/transfer (PIS) intents.
 *
 * <p>The connect flow is interactive: {@link #connect()} returns the authorization
 * URL to open in a browser; once the user completes the consent,
 * {@link #findConnectionId()} returns the created connection and
 * {@link #isActive(String)} confirms it before the id is persisted.
 */
public interface TransactionSource {

    /** Opens a one-time AIS consent and returns the authorization URL to open in a browser. */
    String connect();

    /** Returns the connection created by the most recent consent for this customer. */
    String findConnectionId();

    /** True when the given connection is active (the user completed the consent). */
    boolean isActive(String connectionId);

    /** Lists the accounts available on the active connection. */
    List<Account> listAccounts();

    /** Fetches all transactions for one account on the active connection. */
    List<Transaction> fetchTransactions(String accountId);
}
