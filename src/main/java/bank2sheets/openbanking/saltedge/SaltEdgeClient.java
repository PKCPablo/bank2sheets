package bank2sheets.openbanking.saltedge;

import bank2sheets.config.AppConfig;
import bank2sheets.http.HttpExchange;
import bank2sheets.http.HttpExchange.Response;
import bank2sheets.http.RealHttpExchange;
import bank2sheets.model.Account;
import bank2sheets.model.Transaction;
import bank2sheets.openbanking.TransactionSource;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.StreamSupport;

/**
 * Salt Edge Account Information API v6 client (AIS-only, read consent).
 *
 * <p>App-level authentication uses the {@code App-id}/{@code Secret} headers from
 * {@link AppConfig}. The client never creates payment/transfer (PIS) intents.
 * Tests drive the {@link HttpExchange} seam with an in-memory fake, so no real
 * credentials or network calls happen outside manual owner validation.
 */
public final class SaltEdgeClient implements TransactionSource {

    private static final String BASE_URL = "https://www.saltedge.com/api/v6";
    private static final String AIS_SCOPE_ACCOUNTS = "accounts";
    private static final String AIS_SCOPE_TRANSACTIONS = "transactions";
    private static final String RETURN_TO = "http://localhost:5173/bank2sheets/connected";

    private final AppConfig config;
    private final HttpExchange http;

    public SaltEdgeClient(AppConfig config, HttpExchange http) {
        this.config = config;
        this.http = http;
    }

    /** Builds a client backed by the shared {@link RealHttpExchange}; real calls need configured env vars. */
    public static SaltEdgeClient create(AppConfig config) {
        return new SaltEdgeClient(config, new RealHttpExchange());
    }

    @Override
    public String connect() {
        JsonObject consent = new JsonObject();
        JsonArray scopes = new JsonArray();
        scopes.add(AIS_SCOPE_ACCOUNTS);
        scopes.add(AIS_SCOPE_TRANSACTIONS);
        consent.add("scopes", scopes);
        JsonObject attempt = new JsonObject();
        attempt.addProperty("return_to", RETURN_TO);
        JsonObject data = new JsonObject();
        data.addProperty("customer_id", customerId());
        data.add("consent", consent);
        data.add("attempt", attempt);
        JsonObject body = new JsonObject();
        body.add("data", data);

        Response response = request("POST", "/connections/connect", body.toString());
        JsonObject result = JsonParser.parseString(response.body()).getAsJsonObject();
        return result.getAsJsonObject("data").get("connect_url").getAsString();
    }

    @Override
    public String findConnectionId() {
        Response response = request("GET", "/connections?customer_id=" + customerId(), null);
        JsonObject result = JsonParser.parseString(response.body()).getAsJsonObject();
        JsonObject newest = StreamSupport.stream(result.getAsJsonArray("data").spliterator(), false)
                .map(JsonElement::getAsJsonObject)
                .max(Comparator.comparing(c -> c.get("created_at").getAsString()))
                .orElse(null);
        if (newest == null) {
            throw new SaltEdgeException("No Salt Edge connection found for customer "
                    + config.saltEdgeCustomerId() + "; complete the authorization URL first");
        }
        return newest.get("id").getAsString();
    }

    @Override
    public boolean isActive(String connectionId) {
        Response response = request("GET", "/connections/" + connectionId, null);
        JsonObject result = JsonParser.parseString(response.body()).getAsJsonObject();
        return "active".equals(result.getAsJsonObject("data").get("status").getAsString());
    }

    @Override
    public List<Account> listAccounts() {
        List<Account> accounts = new ArrayList<>();
        for (JsonObject account : fetchPages("/accounts?connection_id=" + config.saltEdgeConnectionId())) {
            accounts.add(new Account(account.get("id").getAsString(), stringOrEmpty(account, "name")));
        }
        return accounts;
    }

    @Override
    public List<Transaction> fetchTransactions(String accountId) {
        String path = "/transactions?connection_id=" + config.saltEdgeConnectionId() + "&account_id=" + accountId;
        List<Transaction> transactions = new ArrayList<>();
        for (JsonObject transaction : fetchPages(path)) {
            transactions.add(new Transaction(
                    transaction.get("id").getAsString(),
                    stringOrEmpty(transaction, "description"),
                    new BigDecimal(transaction.get("amount").getAsString()),
                    transaction.get("currency_code").getAsString(),
                    // Normalize provider variations: made_on is either "YYYY-MM-DD" or a full ISO timestamp.
                    LocalDate.parse(transaction.get("made_on").getAsString().substring(0, 10))));
        }
        return transactions;
    }

    private String customerId() {
        if (config.saltEdgeCustomerId().isBlank()) {
            throw new SaltEdgeException("SALTEDGE_CUSTOMER_ID is required for connect");
        }
        return config.saltEdgeCustomerId();
    }

    /** Follows the {@code meta.next_id} pagination cursor until the last page. */
    private List<JsonObject> fetchPages(String pathAndQuery) {
        List<JsonObject> items = new ArrayList<>();
        String nextId = null;
        do {
            String url = nextId == null ? pathAndQuery : pathAndQuery + "&next_id=" + nextId;
            JsonObject result = JsonParser.parseString(request("GET", url, null).body()).getAsJsonObject();
            for (JsonElement element : result.getAsJsonArray("data")) {
                items.add(element.getAsJsonObject());
            }
            JsonElement next = result.has("meta") ? result.getAsJsonObject("meta").get("next_id") : null;
            nextId = (next == null || next.isJsonNull()) ? null : next.getAsString();
        } while (nextId != null);
        return items;
    }

    private Response request(String method, String pathAndQuery, String body) {
        Map<String, String> headers = Map.of(
                "Accept", "application/json",
                "Content-type", "application/json",
                "App-id", config.saltEdgeAppId(),
                "Secret", config.saltEdgeSecret());
        Response response = http.exchange(method, BASE_URL + pathAndQuery, headers, body);
        if (!response.ok()) {
            throw new SaltEdgeException("Salt Edge request failed (HTTP " + response.status() + "): "
                    + method + " " + pathAndQuery + " -> " + response.body());
        }
        return response;
    }

    private static String stringOrEmpty(JsonObject object, String member) {
        JsonElement value = object.get(member);
        return value == null || value.isJsonNull() ? "" : value.getAsString();
    }
}
