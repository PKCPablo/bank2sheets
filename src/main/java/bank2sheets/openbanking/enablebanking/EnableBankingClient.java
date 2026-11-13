package bank2sheets.openbanking.enablebanking;

import bank2sheets.config.EnableBankingConfig;
import bank2sheets.http.HttpExchange;
import bank2sheets.http.HttpExchange.Response;
import bank2sheets.http.Rs256Jwt;
import bank2sheets.model.Account;
import bank2sheets.model.Transaction;
import bank2sheets.openbanking.TransactionSource;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
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
    private static final String ASPSP_NAME = "BBVA";
    private static final String ASPSP_COUNTRY = "ES";
    private static final String PSU_TYPE = "personal";
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

    /** Sets the session id used by {@link #listAccounts()} (persisted state, wired in Task 2). */
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
        JsonObject bbva = findBbva();
        // maximum_consent_validity is in seconds (e.g. 15552000 = 180 days).
        long validitySeconds = bbva.has("maximum_consent_validity")
                ? bbva.get("maximum_consent_validity").getAsLong() : DEFAULT_CONSENT_VALIDITY_SECONDS;
        pendingState = UUID.randomUUID().toString();

        JsonObject access = new JsonObject();
        access.addProperty("valid_until", now().plusSeconds(validitySeconds).toString());
        JsonObject aspsp = new JsonObject();
        aspsp.addProperty("name", ASPSP_NAME);
        aspsp.addProperty("country", ASPSP_COUNTRY);
        JsonObject body = new JsonObject();
        body.add("access", access);
        body.add("aspsp", aspsp);
        body.addProperty("redirect_url", config.redirectUrl());
        body.addProperty("state", pendingState);
        body.addProperty("psu_type", PSU_TYPE);

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
            throw new EnableBankingException("No authorization in progress; call connect() first");
        }
        String callbackUrl = receiver.awaitCallbackUrl();
        String state = queryParam(callbackUrl, "state");
        if (!pendingState.equals(state)) {
            throw new EnableBankingException("Authorization state mismatch: expected " + pendingState
                    + " but callback carried " + state);
        }
        String code = queryParam(callbackUrl, "code");
        if (code.isBlank()) {
            throw new EnableBankingException("Authorization callback carries no code: " + callbackUrl);
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
            throw new EnableBankingException("Enable Banking request failed (HTTP " + response.status()
                    + "): GET /sessions/" + sessionId + " -> " + response.body());
        }
        return "AUTHORIZED".equals(stringOrEmpty(parse(response.body()), "status"));
    }

    @Override
    public List<Account> listAccounts() {
        if (configSessionId().isBlank()) {
            throw new EnableBankingException("ENABLE_BANKING_SESSION_ID is required to list accounts");
        }
        JsonObject session = request("GET", "/sessions/" + configSessionId(), null);
        if (!session.has("accounts") || !session.get("accounts").isJsonArray()) {
            throw new EnableBankingException(
                    "Enable Banking session response carries no accounts array: " + session);
        }
        // Name fallback: uid -> product from accounts_data, before the empty fallback.
        Map<String, String> products = new HashMap<>();
        if (session.has("accounts_data") && session.get("accounts_data").isJsonArray()) {
            for (JsonElement data : session.getAsJsonArray("accounts_data")) {
                JsonObject entry = data.getAsJsonObject();
                if (entry.has("uid") && !entry.get("uid").isJsonNull()) {
                    products.put(entry.get("uid").getAsString(), stringOrEmpty(entry, "product"));
                }
            }
        }
        List<Account> accounts = new ArrayList<>();
        // accounts is an array of UUID strings; names come from the details endpoint.
        for (JsonElement element : session.getAsJsonArray("accounts")) {
            String uuid = element.getAsString();
            accounts.add(new Account(uuid, accountName(uuid, products.getOrDefault(uuid, ""))));
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
                path += "&continuation_key="
                        + URLEncoder.encode(continuationKey, StandardCharsets.UTF_8);
            }
            JsonObject result = request("GET", path, null);
            for (JsonElement element : result.getAsJsonArray("transactions")) {
                transactions.add(mapTransaction(element.getAsJsonObject()));
            }
            continuationKey = stringOrEmpty(result, "continuation_key");
        } while (!continuationKey.isEmpty());
        return transactions;
    }

    private Transaction mapTransaction(JsonObject t) {
        String id = transactionId(t);
        String currency = stringOrEmpty(t.getAsJsonObject("transaction_amount"), "currency");
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
        String entryReference = stringOrEmpty(t, "entry_reference");
        if (!entryReference.isBlank()) {
            return "eb:" + entryReference + ":" + shortHash(canonical);
        }
        String transactionId = stringOrEmpty(t, "transaction_id");
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
                + stringOrEmpty(t.getAsJsonObject("transaction_amount"), "currency")
                + "|" + description(t) + "|" + stringOrEmpty(t, "reference_number");
    }

    /** Amount with the DBIT sign applied; the same value stored on the {@link Transaction}. */
    private static BigDecimal signedAmount(JsonObject t) {
        BigDecimal value = new BigDecimal(t.getAsJsonObject("transaction_amount").get("amount").getAsString());
        return "DBIT".equals(stringOrEmpty(t, "credit_debit_indicator")) ? value.negate() : value;
    }

    /** booking_date, else value_date, else transaction_date, in ISO {@code YYYY-MM-DD}. */
    private static String resolvedDate(JsonObject t) {
        for (String field : List.of("booking_date", "value_date", "transaction_date")) {
            String date = stringOrEmpty(t, field);
            if (!date.isBlank()) {
                return date.substring(0, 10);
            }
        }
        throw new EnableBankingException("Enable Banking transaction carries no "
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
        boolean debit = "DBIT".equals(stringOrEmpty(t, "credit_debit_indicator"));
        // DBIT = money out (we paid a creditor); CRDT = money in (a debtor paid us).
        // Counterparties are nested objects: {"creditor": {"name": ...}}.
        String party = partyName(t, debit ? "creditor" : "debtor");
        if (party.isBlank()) {
            // Defensive fallback for flat fields.
            party = stringOrEmpty(t, debit ? "creditor_name" : "debtor_name");
        }
        if (!party.isBlank()) {
            return party;
        }
        return stringOrEmpty(t, "note");
    }

    /** {@code name} inside the nested counterparty object, else empty. */
    private static String partyName(JsonObject t, String member) {
        JsonElement party = t.get(member);
        if (party == null || party.isJsonNull() || !party.isJsonObject()) {
            return "";
        }
        return stringOrEmpty(party.getAsJsonObject(), "name");
    }

    /** Parsed resolved date; fails when the payload has none. */
    private static LocalDate madeOn(JsonObject t) {
        return LocalDate.parse(resolvedDate(t));
    }

    /** Display name from the account details endpoint; falls back to product/empty on failure. */
    private String accountName(String uuid, String fallback) {
        try {
            JsonObject details = request("GET", "/accounts/" + uuid + "/details", null);
            String name = stringOrEmpty(details, "name");
            return name.isBlank() ? fallback : name;
        } catch (EnableBankingException e) {
            return fallback;
        }
    }

    private JsonObject findBbva() {
        JsonObject result = request("GET", "/aspsps?country=" + ASPSP_COUNTRY
                + "&psu_type=" + PSU_TYPE + "&service=AIS", null);
        for (JsonElement element : result.getAsJsonArray("aspsps")) {
            JsonObject aspsp = element.getAsJsonObject();
            if (ASPSP_NAME.equals(stringOrEmpty(aspsp, "name"))
                    && ASPSP_COUNTRY.equals(stringOrEmpty(aspsp, "country"))) {
                return aspsp;
            }
        }
        throw new EnableBankingException("Enable Banking lists no " + ASPSP_NAME + " ASPSP in "
                + ASPSP_COUNTRY + " for " + PSU_TYPE + " AIS");
    }

    private String configSessionId() {
        if (sessionId == null || sessionId.isBlank()) {
            throw new EnableBankingException("No Enable Banking session id available; complete the "
                    + "connect flow first (Task 2 wires the persisted session id)");
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
            throw new EnableBankingException("Enable Banking request failed (HTTP " + response.status()
                    + "): " + method + " " + pathAndQuery + " -> " + response.body());
        }
        return parse(response.body());
    }

    private static JsonObject parse(String body) {
        return JsonParser.parseString(body).getAsJsonObject();
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
            throw new EnableBankingException(
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

    private static String stringOrEmpty(JsonObject object, String member) {
        JsonElement value = object.get(member);
        return value == null || value.isJsonNull() ? "" : value.getAsString();
    }

    private static String sha256Hex(String input) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(input.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder();
            for (byte b : digest) {
                hex.append("%02x".formatted(b));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new EnableBankingException("SHA-256 unavailable", e);
        }
    }
}
