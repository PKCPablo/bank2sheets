package bank2sheets;

import bank2sheets.config.AccountConfig;
import bank2sheets.config.AppConfig;
import bank2sheets.config.RuntimeState;
import bank2sheets.config.SheetsConfig;
import bank2sheets.model.Account;
import bank2sheets.model.Transaction;
import bank2sheets.openbanking.ProviderSources;
import bank2sheets.openbanking.TransactionSource;
import bank2sheets.sheets.GoogleSheetsClient;
import bank2sheets.sheets.SheetRow;
import com.google.gson.JsonObject;
import java.io.Console;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * Command line entrypoint for bank2sheets.
 *
 * <p>Dispatch plus the {@code connect}/{@code fetch}/{@code sync} commands live
 * here. Credentials are read from the environment at runtime, never stored in
 * the repository. Only the provider connection/session id is persisted (Salt Edge
 * connection id or Enable Banking session id), in a gitignored runtime state file.
 */
public final class Main {

    private static final String STATE_FILE = "bank2sheets-state.properties";

    private static final String USAGE = """
            Usage: bank2sheets <command>

            Commands:
              connect   Authorize a bank account (one-time AIS-only consent)
              fetch     Fetch transactions for the configured accounts and print them as JSON
              sync      Fetch transactions and sync new ones to Google Sheets (idempotent)
              help      Show this help

            Configuration is read from environment variables at runtime.
            Enable Banking (default provider):
              ENABLE_BANKING_APP_ID, ENABLE_BANKING_PRIVATE_KEY_B64, ENABLE_BANKING_REDIRECT_URL
              ENABLE_BANKING_SESSION_ID (optional once `connect` has run)
            Salt Edge (selectable fallback):
              SALTEDGE_APP_ID, SALTEDGE_SECRET, SALTEDGE_CUSTOMER_ID
              SALTEDGE_CONNECTION_ID (optional once `connect` has run)
            Shared:
              BANK2SHEETS_ACCOUNTS  comma-separated provider:accountId entries
                                    (e.g. enablebanking:<uuid> or saltedge:ACCT_ID);
                                    all entries must use one provider
              GOOGLE_SERVICE_ACCOUNT_B64, GOOGLE_SPREADSHEET_ID (required for `sync`)
            """;

    private final Function<AppConfig, TransactionSource> sources;
    private final Supplier<AppConfig> config;
    private final Supplier<GoogleSheetsClient> sheets;
    private final RuntimeState state;
    private final Runnable waitForEnter;

    /**
     * @param sources factory for the provider client (tests inject a fake HTTP backend)
     * @param config config provider (tests inject a fixed config; real runs read the environment)
     * @param sheets factory for the Google Sheets client (tests inject a fake HTTP backend)
     * @param stateFile runtime state file that {@code connect} writes to (gitignored)
     * @param waitForEnter interactive pause between printing the URL and confirming the connection
     */
    Main(Function<AppConfig, TransactionSource> sources, Supplier<AppConfig> config, Supplier<GoogleSheetsClient> sheets,
            Path stateFile, Runnable waitForEnter) {
        this.sources = sources;
        this.config = config;
        this.sheets = sheets;
        this.state = new RuntimeState(stateFile);
        this.waitForEnter = waitForEnter;
    }

    public static void main(String[] args) {
        System.exit(run(args));
    }

    /**
     * Dispatches to the requested subcommand and returns the process exit code.
     * Split from {@link #main(String[])} so tests exercise it without System.exit.
     */
    public static int run(String[] args) {
        return new Main(ProviderSources::create, AppConfig::fromEnv,
                () -> GoogleSheetsClient.create(SheetsConfig.fromEnv()), Path.of(STATE_FILE),
                Main::interactiveWait)
                .dispatch(args);
    }

    int dispatch(String[] args) {
        if (args.length == 0 || "help".equals(args[0])) {
            System.out.print(USAGE);
            return 0;
        }
        switch (args[0]) {
            case "connect" -> {
                return connect();
            }
            case "fetch" -> {
                return fetch();
            }
            case "sync" -> {
                return sync();
            }
            default -> {
                System.err.println("Unknown command: " + args[0]);
                System.err.print(USAGE);
                return 1;
            }
        }
    }

    /** One-time AIS consent: print the authorization URL, then persist the active connection id. */
    private int connect() {
        AppConfig cfg = config.get();
        try {
            String provider = cfg.provider();
            TransactionSource source = sources.apply(cfg);
            String connectUrl = source.connect();
            System.out.println("Open this URL in your browser and authorize BBVA (read-only consent):");
            System.out.println();
            System.out.println("  " + connectUrl);
            System.out.println();
            waitForEnter.run();
            String connectionId = source.findConnectionId();
            if (!source.isActive(connectionId)) {
                System.err.println("connect: connection " + connectionId + " is not active yet; "
                        + "complete the authorization and run `connect` again");
                return 1;
            }
            state.save(provider, connectionId);
            System.out.println("Connected. Connection id " + connectionId + " saved to " + state.file());
            try {
                TransactionSource connected = sources.apply(cfg.withProviderState(provider, connectionId));
                System.out.println("Available accounts:");
                for (Account account : connected.listAccounts()) {
                    System.out.println("  " + account.id() + (account.name().isBlank() ? "" : " (" + account.name() + ")"));
                }
                System.out.println("Set BANK2SHEETS_ACCOUNTS=" + provider + ":<accountId> for `fetch`/`sync`.");
            } catch (Bank2SheetsException | IllegalStateException e) {
                System.err.println("connect: could not list accounts: " + e.getMessage());
            }
            return 0;
        } catch (Bank2SheetsException | IllegalStateException e) {
            System.err.println("connect: " + e.getMessage());
            return 1;
        }
    }

    /** Fetches transactions for every configured account and prints them as JSON lines. */
    private int fetch() {
        AppConfig resolved = requireFetchConfig("fetch");
        if (resolved == null) {
            return 1;
        }
        try {
            TransactionSource source = sources.apply(resolved);
            Map<String, String> accountNames = new HashMap<>();
            for (Account account : source.listAccounts()) {
                accountNames.put(account.id(), account.name());
            }
            for (AccountConfig account : resolved.accounts()) {
                List<Transaction> transactions = source.fetchTransactions(account.providerAccountId());
                String name = accountNames.getOrDefault(account.providerAccountId(), "");
                System.out.println("Account " + account.providerAccountId()
                        + (name.isBlank() ? "" : " (" + name + ")") + ": " + transactions.size() + " transaction(s)");
                for (Transaction transaction : transactions) {
                    System.out.println(toJson(transaction));
                }
            }
            return 0;
        } catch (Bank2SheetsException | IllegalStateException e) {
            System.err.println("fetch: " + e.getMessage());
            return 1;
        }
    }

    /** Prefers the env id; falls back to the provider id persisted by {@code connect}. */
    private AppConfig withProviderState(AppConfig cfg) {
        String provider = cfg.provider();
        if (!cfg.providerId().isBlank()) {
            return cfg;
        }
        String stored = state.read(provider);
        return stored == null ? cfg : cfg.withProviderState(provider, stored);
    }

    /**
     * Guards shared by {@code fetch} and {@code sync}: accounts, provider state,
     * and provider credentials must be configured. Prints the command-prefixed
     * error and returns {@code null} when a guard fails.
     */
    private AppConfig requireFetchConfig(String command) {
        AppConfig cfg = config.get();
        if (cfg.accounts().isEmpty()) {
            System.err.println(command + ": no accounts configured; set BANK2SHEETS_ACCOUNTS "
                    + "(e.g. enablebanking:<uuid> or saltedge:ACCT_ID)");
            return null;
        }
        AppConfig resolved = withProviderState(cfg);
        if (resolved.providerId().isBlank()) {
            String hint = "enablebanking".equals(resolved.provider())
                    ? "ENABLE_BANKING_SESSION_ID" : "SALTEDGE_CONNECTION_ID";
            System.err.println(command + ": no connection; run `connect` once or set " + hint);
            return null;
        }
        return resolved;
    }

    /**
     * Fetches transactions for every configured account and appends the ones not
     * already present in the spreadsheet. Dedupe uses the TransactionId column, so
     * repeated runs append nothing new (idempotency). Exits 0 on success and 1 on
     * missing configuration or provider/Sheets errors.
     */
    private int sync() {
        AppConfig resolved = requireFetchConfig("sync");
        if (resolved == null) {
            return 1;
        }
        try {
            GoogleSheetsClient sheetsClient = sheets.get();
            Set<String> existing = sheetsClient.existingTransactionIds();
            TransactionSource source = sources.apply(resolved);
            List<SheetRow> toAppend = new ArrayList<>();
            int fetched = 0;
            for (AccountConfig account : resolved.accounts()) {
                for (Transaction transaction : source.fetchTransactions(account.providerAccountId())) {
                    fetched++;
                    if (!existing.add(transaction.id())) {
                        continue;
                    }
                    toAppend.add(new SheetRow(transaction.madeOn().toString(), transaction.description(),
                            transaction.amount(), transaction.currency(), transaction.id()));
                }
            }
            sheetsClient.appendRows(toAppend);
            System.out.println("sync: fetched " + fetched + " transaction(s), appended " + toAppend.size()
                    + " new row(s), skipped " + (fetched - toAppend.size()) + " duplicate(s)");
            return 0;
        } catch (Bank2SheetsException | IllegalStateException e) {
            System.err.println("sync: " + e.getMessage());
            return 1;
        }
    }

    private static String toJson(Transaction transaction) {
        JsonObject json = new JsonObject();
        json.addProperty("id", transaction.id());
        json.addProperty("description", transaction.description());
        json.addProperty("amount", transaction.amount());
        json.addProperty("currency", transaction.currency());
        json.addProperty("madeOn", transaction.madeOn().toString());
        return json.toString();
    }

    /** Waits for Enter only when running in an interactive terminal. */
    private static void interactiveWait() {
        Console console = System.console();
        if (console != null) {
            console.readLine("After completing the authorization, press Enter to continue...");
        }
    }
}
