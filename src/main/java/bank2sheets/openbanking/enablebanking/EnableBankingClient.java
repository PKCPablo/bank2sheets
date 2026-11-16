package bank2sheets.openbanking.enablebanking;

import bank2sheets.Bank2SheetsException;

import bank2sheets.config.EnableBankingConfig;
import bank2sheets.http.HttpExchange;
import bank2sheets.http.HttpExchange.Response;
import bank2sheets.http.JsonSupport;
import bank2sheets.http.Rs256Jwt;
import bank2sheets.model.Account;
import bank2sheets.model.Transaction;
import bank2sheets.openbanking.TransactionSource;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.net.URI;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.util.function.LongSupplier;

/**
 * Enable Banking Account Information API client (AIS-only, read consent) for
 * the restricted-production flow (personal use, owner-linked accounts).
 *
 * <p>App-level authentication is an RS256 JWT bearer token: header
 * {@code typ=JWT, alg=RS256, kid=<application id>}; claims
 * {@code iss=enablebanking.com, aud=api.enablebanking.com, iat, exp} (TTL
 * 3600 s, API max 86400 s). The client never creates payment (PIS) intents.
 * Tests drive the {@link HttpExchange} seam with an in-memory fake, so no real
 * credentials or network calls happen outside manual owner validation.
 */
public final class EnableBankingClient implements TransactionSource {

    private static final String BASE_URL = "https://api.enablebanking.com";
    private static final long JWT_TTL_SECONDS = 3600;
    /** Fallback consent validity in seconds (90 days) when the ASPSP does not report one. */
    private static final long DEFAULT_CONSENT_VALIDITY_SECONDS = 90 * 86400;

    private final EnableBankingConfig config;
    private final HttpExchange http;
    private final AuthorizationCodeReceiver receiver;
    private final LongSupplier epochSeconds;
    private String bearerToken;
    private long bearerTokenExpiresAt;
    private String pendingState;
    private String sessionId;

    /** Sets the session id used by {@link #listAccounts()}. */
    public void setSessionId(String sessionId) {
        this.sessionId = sessionId;
    }

    public EnableBankingClient(EnableBankingConfig config, HttpExchange http,
            AuthorizationCodeReceiver receiver, LongSupplier epochSeconds) {
        this.config = config;
        this.http = http;
        this.receiver = receiver;
        this.epochSeconds = epochSeconds;
    }

    public EnableBankingClient(EnableBankingConfig config, HttpExchange http,
            AuthorizationCodeReceiver receiver) {
        this(config, http, receiver, () -> Instant.now().getEpochSecond());
    }

    @Override
    public String connect() {
        JsonObject listed = findAspsp();
        // maximum_consent_validity is in seconds (e.g. 15552000 = 180 days).
        long validitySeconds = listed.has("maximum_consent_validity")
                ? listed.get("maximum_consent_validity").getAsLong() : DEFAULT_CONSENT_VALIDITY_SECONDS;
        pendingState = UUID.randomUUID().toString();

        JsonObject access = new JsonObject();
        access.addProperty("valid_until", now().plusSeconds(validitySeconds).toString());
        JsonObject aspsp = new JsonObject();
        aspsp.addProperty("name", config.aspspName());
        aspsp.addProperty("country", config.aspspCountry());
        JsonObject body = new JsonObject();
        body.add("access", access);
        body.add("aspsp", aspsp);
        body.addProperty("redirect_url", config.redirectUrl());
        body.addProperty("state", pendingState);
        body.addProperty("psu_type", config.psuType());

        JsonObject result = request("POST", "/auth", body.toString());
        String url = result.get("url").getAsString();
        // Bind the loopback listener before the URL is opened, so the browser
        // redirect cannot reach a closed port while the user completes SCA.
        receiver.start();
        return url;
    }

    @Override
    public String findConnectionId() {
        if (pendingState == null) {
            throw new Bank2SheetsException("No authorization in progress; call connect() first");
        }
        String callbackUrl = receiver.awaitCallbackUrl();
        String state = queryParam(callbackUrl, "state");
        if (!pendingState.equals(state)) {
            throw new Bank2SheetsException("Authorization state mismatch: expected " + pendingState
                    + " but callback carried " + state);
        }
        String code = queryParam(callbackUrl, "code");
        if (code.isBlank()) {
            throw new Bank2SheetsException("Authorization callback carries no code: " + callbackUrl);
        }
        JsonObject body = new JsonObject();
        body.addProperty("code", code);
        JsonObject result = request("POST", "/sessions", body.toString());
        return result.get("session_id").getAsString();
    }

    @Override
    public boolean isActive(String sessionId) {
        Response response = exchange("GET", "/sessions/" + sessionId, null);
        // 404 = expired/removed session: not active, not an error.
        if (response.status() == 404) {
            return false;
        }
        if (!response.ok()) {
            throw failure("GET", "/sessions/" + sessionId, response);
        }
        return "AUTHORIZED".equals(JsonSupport.stringOrEmpty(JsonSupport.parseObject(response.body()), "status"));
    }

    @Override
    public List<Account> listAccounts() {
        String sessionId = configSessionId();
        JsonObject session = request("GET", "/sessions/" + sessionId, null);
        if (!session.has("accounts") || !session.get("accounts").isJsonArray()) {
            throw new Bank2SheetsException(
                    "Enable Banking session response carries no accounts array: " + session);
        }
        List<Account> accounts = new ArrayList<>();
        // accounts is an array of UUID strings; names come from the details endpoint.
        for (JsonElement element : session.getAsJsonArray("accounts")) {
            String uuid = element.getAsString();
            accounts.add(new Account(uuid, accountName(uuid)));
        }
        return accounts;
    }

    @Override
    public List<Transaction> fetchTransactions(String accountId) {
        List<Transaction> transactions = new ArrayList<>();
        String continuationKey = null;
        do {
            String path = "/accounts/" + accountId + "/transactions?transaction_status=BOOK";
            if (continuationKey != null) {
                path += "&continuation_key=" + encode(continuationKey);
            }
            JsonObject result = request("GET", path, null);
            for (JsonElement element : result.getAsJsonArray("transactions")) {
                transactions.add(mapTransaction(element.getAsJsonObject()));
            }
            continuationKey = JsonSupport.stringOrEmpty(result, "continuation_key");
        } while (!continuationKey.isEmpty());
        return transactions;
    }

    private Transaction mapTransaction(JsonObject t) {
        String id = transactionId(t);
        String currency = JsonSupport.stringOrEmpty(t.getAsJsonObject("transaction_amount"), "currency");
        return new Transaction(id, description(t), signedAmount(t), currency, madeOn(t));
    }

    /**
     * {@code eb:} + entry_reference, else transaction_id, else a sha256 over the
     * canonical fields. {@code entry_reference} and {@code transaction_id} are
     * not guaranteed unique by the API, so reference-based ids carry a 16-hex
     * hash suffix of the canonical fields: two distinct transactions sharing a
     * reference keep distinct dedupe ids instead of silently collapsing into
     * one sheet row.
     */
    private static String transactionId(JsonObject t) {
        String canonical = canonicalFields(t);
        String entryReference = JsonSupport.stringOrEmpty(t, "entry_reference");
        if (!entryReference.isBlank()) {
            return "eb:" + entryReference + ":" + shortHash(canonical);
        }
        String transactionId = JsonSupport.stringOrEmpty(t, "transaction_id");
        if (!transactionId.isBlank()) {
            return "eb:" + transactionId + ":" + shortHash(canonical);
        }
        return "eb:sha256:" + sha256Hex(canonical);
    }

    /** First 16 hex characters of the canonical-fields sha256. */
    private static String shortHash(String canonical) {
        return sha256Hex(canonical).substring(0, 16);
    }

    /**
     * Deterministic id fallback: only the normalized values that also build the
     * {@link Transaction} (resolved date, signed amount, currency, description,
     * reference), so a re-fetch with changed volatile fields (status, balances,
     * exchange rates, transaction_id) still yields the same id.
     */
    private static String canonicalFields(JsonObject t) {
        return resolvedDate(t) + "|" + signedAmount(t).toPlainString() + "|"
                + JsonSupport.stringOrEmpty(t.getAsJsonObject("transaction_amount"), "currency")
                + "|" + description(t) + "|" + JsonSupport.stringOrEmpty(t, "reference_number");
    }

    /** Amount with the DBIT sign applied; the same value stored on the {@link Transaction}. */
    private static BigDecimal signedAmount(JsonObject t) {
        BigDecimal value = new BigDecimal(t.getAsJsonObject("transaction_amount").get("amount").getAsString());
        return "DBIT".equals(JsonSupport.stringOrEmpty(t, "credit_debit_indicator")) ? value.negate() : value;
    }

    /** booking_date, else value_date, else transaction_date, in ISO {@code YYYY-MM-DD}. */
    private static String resolvedDate(JsonObject t) {
        for (String field : List.of("booking_date", "value_date", "transaction_date")) {
            String date = JsonSupport.stringOrEmpty(t, field);
            if (!date.isBlank()) {
                return date.substring(0, 10);
            }
        }
        throw new Bank2SheetsException("Enable Banking transaction carries no "
                + "booking_date/value_date/transaction_date: " + t);
    }

    /** Remittance lines joined, else creditor/debtor name by direction, else note, else empty. */
    private static String description(JsonObject t) {
        JsonElement remittance = t.get("remittance_information");
        if (remittance != null && !remittance.isJsonNull()) {
            StringBuilder joined = new StringBuilder();
            if (remittance.isJsonArray()) {
                for (JsonElement line : remittance.getAsJsonArray()) {
                    if (joined.length() > 0) {
                        joined.append(' ');
                    }
                    joined.append(line.getAsString());
                }
            } else {
                joined.append(remittance.getAsString());
            }
            if (!joined.isEmpty()) {
                return joined.toString();
            }
        }
        boolean debit = "DBIT".equals(JsonSupport.stringOrEmpty(t, "credit_debit_indicator"));
        // DBIT = money out (we paid a creditor); CRDT = money in (a debtor paid us).
        // Counterparties are nested objects: {"creditor": {"name": ...}}.
        String party = partyName(t, debit ? "creditor" : "debtor");
        if (party.isBlank()) {
            // Defensive fallback for flat fields.
            party = JsonSupport.stringOrEmpty(t, debit ? "creditor_name" : "debtor_name");
        }
        if (!party.isBlank()) {
            return party;
        }
        return JsonSupport.stringOrEmpty(t, "note");
    }

    /** {@code name} inside the nested counterparty object, else empty. */
    private static String partyName(JsonObject t, String member) {
        JsonElement party = t.get(member);
        if (party == null || party.isJsonNull() || !party.isJsonObject()) {
            return "";
        }
        return JsonSupport.stringOrEmpty(party.getAsJsonObject(), "name");
    }

    /** Parsed resolved date; fails when the payload has none. */
    private static LocalDate madeOn(JsonObject t) {
        return LocalDate.parse(resolvedDate(t));
    }

    /** Display name from the account details endpoint; empty on failure (caller falls back to the id). */
    private String accountName(String uuid) {
        try {
            return JsonSupport.stringOrEmpty(request("GET", "/accounts/" + uuid + "/details", null), "name");
        } catch (Bank2SheetsException e) {
            return "";
        }
    }

    /** Looks up the configured ASPSP via {@code GET /aspsps}; fails fast when it is not listed. */
    private JsonObject findAspsp() {
        JsonObject result = request("GET", "/aspsps?country="
                + encode(config.aspspCountry()) + "&psu_type=" + encode(config.psuType())
                + "&service=AIS", null);
        for (JsonElement element : result.getAsJsonArray("aspsps")) {
            JsonObject aspsp = element.getAsJsonObject();
            if (config.aspspName().equals(JsonSupport.stringOrEmpty(aspsp, "name"))
                    && config.aspspCountry().equals(JsonSupport.stringOrEmpty(aspsp, "country"))) {
                return aspsp;
            }
        }
        throw new Bank2SheetsException("Enable Banking lists no " + config.aspspName() + " ASPSP in "
                + config.aspspCountry() + " for " + config.psuType() + " AIS");
    }

    /** URL-encodes a query value (UTF-8), so names with spaces stay valid. */
    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private String configSessionId() {
        if (sessionId == null || sessionId.isBlank()) {
            throw new Bank2SheetsException("No Enable Banking session id available; complete the "
                    + "connect flow first");
        }
        return sessionId;
    }

    /** Sends the request and returns the raw response (callers decide how to map non-2xx). */
    private Response exchange(String method, String pathAndQuery, String body) {
        Map<String, String> headers = new HashMap<>();
        headers.put("Accept", "application/json");
        headers.put("Authorization", "Bearer " + bearerToken());
        if (body != null) {
            headers.put("Content-Type", "application/json");
        }
        return http.exchange(method, BASE_URL + pathAndQuery, headers, body);
    }

    private JsonObject request(String method, String pathAndQuery, String body) {
        Response response = exchange(method, pathAndQuery, body);
        if (!response.ok()) {
            throw failure(method, pathAndQuery, response);
        }
        return JsonSupport.parseObject(response.body());
    }

    /**
     * Builds the non-2xx failure message. A {@code 429} whose body carries
     * {@code ASPSP_RATE_LIMIT_EXCEEDED} (the BBVA {@code [HUB046]} case) means
     * the bank's background AIS allowance for this consent is spent, so the
     * message appends concrete recovery guidance; the raw body is always kept
     * for diagnostics.
     */
    private static Bank2SheetsException failure(String method, String path, Response response) {
        String message = "Enable Banking request failed (HTTP " + response.status()
                + "): " + method + " " + path + " -> " + response.body();
        if (response.status() == 429
                && response.body().toUpperCase(java.util.Locale.ROOT).contains("ASPSP_RATE_LIMIT_EXCEEDED")) {
            message += " The bank's background AIS access allowance for this consent is used up"
                    + " (ASPSP_RATE_LIMIT_EXCEEDED; commonly ~4 accesses per 24 h)."
                    + " Retrying now will not help: wait ~6 hours or until the next day,"
                    + " or run `connect` for a fresh consent.";
        }
        return new Bank2SheetsException(message);
    }

    /**
     * Builds and caches the RS256 JWT bearer token for the application,
     * rebuilding it shortly before expiry (TTL 3600 s, with a 60 s safety
     * margin so an in-flight request never uses a token the API rejects).
     */
    private String bearerToken() {
        long now = epochSeconds.getAsLong();
        if (bearerToken != null && now < bearerTokenExpiresAt - 60) {
            return bearerToken;
        }
        JsonObject header = new JsonObject();
        header.addProperty("typ", "JWT");
        header.addProperty("alg", "RS256");
        header.addProperty("kid", config.appId());
        JsonObject claims = new JsonObject();
        claims.addProperty("iss", "enablebanking.com");
        claims.addProperty("aud", "api.enablebanking.com");
        claims.addProperty("iat", now);
        claims.addProperty("exp", now + JWT_TTL_SECONDS);
        bearerToken = Rs256Jwt.encode(header.toString(), claims.toString(), privateKeyPem());
        bearerTokenExpiresAt = now + JWT_TTL_SECONDS;
        return bearerToken;
    }

    /** Decodes the base64 env value into the PKCS#8 PEM text. */
    private String privateKeyPem() {
        try {
            return new String(java.util.Base64.getDecoder().decode(config.privateKeyB64()),
                    StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) {
            throw new Bank2SheetsException(
                    "ENABLE_BANKING_PRIVATE_KEY_B64 is not valid base64 (expected base64 PKCS#8 PEM)", e);
        }
    }

    private Instant now() {
        return Instant.ofEpochSecond(epochSeconds.getAsLong());
    }

    private static String queryParam(String url, String name) {
        String query = URI.create(url).getRawQuery();
        if (query == null) {
            return "";
        }
        for (String pair : query.split("&")) {
            int eq = pair.indexOf('=');
            if (eq > 0 && URLDecoder.decode(pair.substring(0, eq), StandardCharsets.UTF_8).equals(name)) {
                return URLDecoder.decode(pair.substring(eq + 1), StandardCharsets.UTF_8);
            }
        }
        return "";
    }

    private static String sha256Hex(String input) {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256")
                            .digest(input.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new Bank2SheetsException("SHA-256 unavailable", e);
        }
    }
}
