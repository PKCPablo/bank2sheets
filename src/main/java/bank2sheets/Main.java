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
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * Command line entrypoint for bank2sheets.
 *
 * <p>Dispatch plus the {@code connect}/{@code fetch}/{@code sync} commands live
 * here. Credentials are read from the environment at runtime, never stored in
 * the repository. Only the Enable Banking session id is persisted, in a
 * gitignored runtime state file.
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
            Enable Banking:
              ENABLE_BANKING_APP_ID, ENABLE_BANKING_PRIVATE_KEY_B64, ENABLE_BANKING_REDIRECT_URL
              ENABLE_BANKING_SESSION_ID (optional once `connect` has run)
              ENABLE_BANKING_ASPSP_NAME, ENABLE_BANKING_ASPSP_COUNTRY, ENABLE_BANKING_PSU_TYPE
                                    (optional bank selection; default BBVA/ES/personal)
            Shared:
              BANK2SHEETS_ACCOUNTS  comma-separated provider:accountId entries
                                    (e.g. enablebanking:<uuid>);
                                    all entries must use one provider
              GOOGLE_SERVICE_ACCOUNT_B64, GOOGLE_SPREADSHEET_ID (required for `sync`)
            """;

    private final Function<AppConfig, TransactionSource> sources;
    private final Supplier<AppConfig> config;
    private final Supplier<GoogleSheetsClient> sheets;
    private final RuntimeState state;
    private final Runnable waitForEnter;
    private final Supplier<YearMonth> currentMonth;

    /**
     * @param sources factory for the provider client (tests inject a fake HTTP backend)
     * @param config config provider (tests inject a fixed config; real runs read the environment)
     * @param sheets factory for the Google Sheets client (tests inject a fake HTTP backend)
     * @param stateFile runtime state file that {@code connect} writes to (gitignored)
     * @param waitForEnter interactive pause between printing the URL and confirming the connection
     */
    Main(Function<AppConfig, TransactionSource> sources, Supplier<AppConfig> config, Supplier<GoogleSheetsClient> sheets,
            Path stateFile, Runnable waitForEnter) {
        this(sources, config, sheets, stateFile, waitForEnter, YearMonth::now);
    }

    /**
     * Same as above with an injectable current month, so tests can pin the
     * month used by the post-append protection pass in {@code sync}.
     */
    Main(Function<AppConfig, TransactionSource> sources, Supplier<AppConfig> config, Supplier<GoogleSheetsClient> sheets,
            Path stateFile, Runnable waitForEnter, Supplier<YearMonth> currentMonth) {
        this.sources = sources;
        this.config = config;
        this.sheets = sheets;
        this.state = new RuntimeState(stateFile);
        this.waitForEnter = waitForEnter;
        this.currentMonth = currentMonth;
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
            System.out.println("Open this URL in your browser and authorize your bank (read-only consent):");
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
                TransactionSource connected = sources.apply(cfg.withProviderState(connectionId));
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
            Map<String, String> names = accountLabels(resolved, source);
            for (AccountConfig account : resolved.accounts()) {
                List<Transaction> transactions = source.fetchTransactions(account.providerAccountId());
                String name = accountLabel(account, names);
                System.out.println("Account " + account.providerAccountId() + " (" + name + "): "
                        + transactions.size() + " transaction(s)");
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
        return stored == null ? cfg : cfg.withProviderState(stored);
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
                    + "(e.g. enablebanking:<uuid>)");
            return null;
        }
        AppConfig resolved = withProviderState(cfg);
        if (resolved.providerId().isBlank()) {
            System.err.println(command + ": no connection; run `connect` once or set ENABLE_BANKING_SESSION_ID");
            return null;
        }
        return resolved;
    }

    /**
     * Fetches transactions for every configured account and appends the ones not
     * already present in their month tab. Each row is routed to the tab titled
     * with the Spanish month name and year (e.g. {@code Enero 2026}, from
     * {@code madeOn()}) and labeled with the source account (configured label,
     * else provider display name, falling back to the account id); tabs are
     * created on demand. When every configured account has a label, the
     * {@code listAccounts()} session/details calls are skipped entirely. Each
     * month's new rows are appended in ascending {@code Fecha} order. Dedupe is
     * exact on the transaction id against the hidden {@code _sync} ledger (per
     * month tab), plus an in-run id set, so repeated runs append nothing new
     * (idempotency). Same-date rows are written in reverse provider order
     * (chronological, under the newest-first provider contract); {@code fetch}
     * is unchanged and keeps printing the raw provider order. After appending,
     * a warning-only protection pass marks
     * every closed month tab (see {@link GoogleSheetsClient#protectCompletedMonths}).
     * Exits 0 on success and 1 on missing configuration or
     * provider/Sheets errors.
     */
    private int sync() {
        AppConfig resolved = requireFetchConfig("sync");
        if (resolved == null) {
            return 1;
        }
        try {
            GoogleSheetsClient sheetsClient = sheets.get();
            TransactionSource source = sources.apply(resolved);
            Map<String, String> labels = accountLabels(resolved, source);
            Map<YearMonth, List<SheetRow>> byMonth = new TreeMap<>();
            Set<String> seen = new HashSet<>();
            int fetched = 0;
            for (AccountConfig account : resolved.accounts()) {
                String label = accountLabel(account, labels);
                List<Transaction> accountTransactions = source.fetchTransactions(account.providerAccountId());
                // The provider lists newest first; reverse so same-date rows read chronologically.
                for (Transaction transaction : accountTransactions.reversed()) {
                    fetched++;
                    if (!seen.add(transaction.id())) {
                        continue;
                    }
                    YearMonth month = YearMonth.from(transaction.madeOn());
                    byMonth.computeIfAbsent(month, m -> new ArrayList<>())
                            .add(new SheetRow(transaction.madeOn(), transaction.amount(),
                                    transaction.description(), label, transaction.id()));
                }
            }
            int appended = 0;
            for (Map.Entry<YearMonth, List<SheetRow>> entry : byMonth.entrySet()) {
                YearMonth month = entry.getKey();
                Set<String> existing = sheetsClient.existingTransactionIds(month);
                List<SheetRow> fresh = new ArrayList<>();
                for (SheetRow row : entry.getValue()) {
                    if (existing.add(row.transactionId())) {
                        fresh.add(row);
                    }
                }
                if (!fresh.isEmpty()) {
                    sheetsClient.appendRows(month, fresh);
                    appended += fresh.size();
                }
            }
            int protectedMonths = sheetsClient.protectCompletedMonths(currentMonth.get());
            System.out.println("sync: fetched " + fetched + " transaction(s), appended " + appended
                    + " new row(s), skipped " + (fetched - appended) + " duplicate(s), protected "
                    + protectedMonths + " closed month tab(s)");
            return 0;
        } catch (Bank2SheetsException | IllegalStateException e) {
            System.err.println("sync: " + e.getMessage());
            return 1;
        }
    }

    /** Configured label, else provider display name, else the provider account id. */
    private static String accountLabel(AccountConfig account, Map<String, String> names) {
        String label = account.label();
        if (label.isBlank()) {
            label = names.getOrDefault(account.providerAccountId(), "");
        }
        return label.isBlank() ? account.providerAccountId() : label;
    }

    /**
     * Account id -> display name from one {@code listAccounts()} call, or an
     * empty map when every configured account already has a label (so no
     * session/details accesses are spent).
     */
    private static Map<String, String> accountLabels(AppConfig cfg, TransactionSource source) {
        if (cfg.accounts().stream().noneMatch(account -> account.label().isBlank())) {
            return Map.of();
        }
        Map<String, String> names = new HashMap<>();
        for (Account account : source.listAccounts()) {
            names.put(account.id(), account.name());
        }
        return names;
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
