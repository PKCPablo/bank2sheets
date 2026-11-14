package bank2sheets.sheets;

import bank2sheets.Bank2SheetsException;

import bank2sheets.config.SheetsConfig;
import bank2sheets.http.HttpExchange;
import bank2sheets.http.HttpExchange.Response;
import bank2sheets.http.RealHttpExchange;
import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonSyntaxException;
import bank2sheets.http.Rs256Jwt;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Google Sheets client backed by a service account (JWT bearer grant). No Google
 * client library: the JDK's {@link java.net.http.HttpClient}/{@link Signature} plus the already
 * installed Gson cover HTTP, RS256 JWT signing, and JSON (ponytail: stdlib rung 3
 * and installed-dependency rung 5; a Google API client would pull a large
 * dependency tree for two endpoints). Tests drive the {@link HttpExchange} seam
 * with an in-memory fake, so no real Google API call happens outside manual owner
 * validation.
 *
 * <p>Target layout (first sheet, columns A..E):
 * {@code Fecha, Descripción, Importe, Moneda, TransactionId}. Rows are appended
 * with {@code valueInputOption=RAW}, so amounts sent as JSON numbers become
 * numeric cells and descriptive text stays literal (no date/formula parsing).
 * The header row is written once when missing. TransactionId is the dedupe key.
 */
public final class GoogleSheetsClient {

    private static final String API_BASE = "https://sheets.googleapis.com/v4/spreadsheets";
    private static final String TOKEN_URI = "https://oauth2.googleapis.com/token";
    private static final String SCOPE = "https://www.googleapis.com/auth/spreadsheets";
    private static final String RANGE = "A1:E";
    private static final String HEADER_RANGE = "A1:E1";
    private static final String IDS_RANGE = "E2:E";
    private static final String VALUE_INPUT_OPTION = "RAW";
    /** Header row; order must match {@link SheetRow#values()} (columns A..E). */
    private static final List<Object> HEADER_VALUES =
            List.<Object>of("Fecha", "Descripción", "Importe", "Moneda", "TransactionId");
    private static final Gson GSON = new Gson();

    private final SheetsConfig config;
    private final HttpExchange http;
    private String accessToken;

    public GoogleSheetsClient(SheetsConfig config, HttpExchange http) {
        this.config = config;
        this.http = http;
    }

    /** Builds a client backed by the shared {@link RealHttpExchange}; fails fast when env config is missing. */
    public static GoogleSheetsClient create(SheetsConfig config) {
        if (config.serviceAccountB64().isBlank()) {
            throw new Bank2SheetsException("GOOGLE_SERVICE_ACCOUNT_B64 must be set (base64 service account JSON)");
        }
        if (config.spreadsheetId().isBlank()) {
            throw new Bank2SheetsException("GOOGLE_SPREADSHEET_ID must be set (target spreadsheet id)");
        }
        return new GoogleSheetsClient(config, new RealHttpExchange());
    }

    /** Returns the transaction ids already present in the TransactionId column (header excluded). */
    public Set<String> existingTransactionIds() {
        JsonObject result = readValues(IDS_RANGE, accessToken());
        Set<String> ids = new HashSet<>();
        JsonArray values = result.has("values") ? result.getAsJsonArray("values") : new JsonArray();
        for (JsonElement row : values) {
            JsonArray cells = row.getAsJsonArray();
            if (!cells.isEmpty() && !cells.get(0).isJsonNull()) {
                String id = cells.get(0).getAsString();
                if (!id.isBlank()) {
                    ids.add(id);
                }
            }
        }
        return ids;
    }

    /**
     * Appends the given rows in one batch after the header. When the sheet has no
     * header yet, the header row is written first so a fresh spreadsheet gets the
     * expected columns. Empty input does nothing.
     */
    public void appendRows(List<SheetRow> rows) {
        if (rows.isEmpty()) {
            return;
        }
        String token = accessToken();
        if (!hasHeader(token)) {
            appendValues(token, toValuesJson(List.of(HEADER_VALUES)));
        }
        appendValues(token, toValuesJson(rows.stream().map(SheetRow::values).toList()));
    }

    /** Serializes rows to the append body: {@code {"values": [[...]]}} with amounts as JSON numbers. */
    static String toValuesJson(List<List<Object>> rows) {
        return GSON.toJson(Map.of("values", rows));
    }

    private boolean hasHeader(String token) {
        JsonObject result = readValues(HEADER_RANGE, token);
        if (!result.has("values")) {
            return false;
        }
        JsonArray values = result.getAsJsonArray("values");
        if (values.isEmpty()) {
            return false;
        }
        JsonArray firstRow = values.get(0).getAsJsonArray();
        return !firstRow.isEmpty() && !firstRow.get(0).isJsonNull()
                && HEADER_VALUES.get(0).equals(firstRow.get(0).getAsString());
    }

    /**
     * Obtains an OAuth access token via the service account JWT bearer grant
     * (RFC 7523) and caches it for the process lifetime; a token lasts ~1h and a
     * single sync run finishes in seconds.
     */
    private String accessToken() {
        if (accessToken != null) {
            return accessToken;
        }
        JsonObject serviceAccount = parseServiceAccount();
        JsonElement email = serviceAccount.get("client_email");
        JsonElement privateKey = serviceAccount.get("private_key");
        if (email == null || email.isJsonNull() || privateKey == null || privateKey.isJsonNull()) {
            throw new Bank2SheetsException("GOOGLE_SERVICE_ACCOUNT_B64 is not a service account JSON "
                    + "(missing client_email/private_key)");
        }
        String tokenUri = serviceAccount.has("token_uri") && !serviceAccount.get("token_uri").isJsonNull()
                ? serviceAccount.get("token_uri").getAsString() : TOKEN_URI;

        JsonObject header = new JsonObject();
        header.addProperty("alg", "RS256");
        header.addProperty("typ", "JWT");
        long now = Instant.now().getEpochSecond();
        JsonObject claims = new JsonObject();
        claims.addProperty("iss", email.getAsString());
        claims.addProperty("scope", SCOPE);
        claims.addProperty("aud", tokenUri);
        claims.addProperty("iat", now);
        claims.addProperty("exp", now + 3600);
        String assertion = Rs256Jwt.encode(header.toString(), claims.toString(), privateKey.getAsString());

        String form = "grant_type="
                + URLEncoder.encode("urn:ietf:params:oauth:grant-type:jwt-bearer", StandardCharsets.UTF_8)
                + "&assertion=" + URLEncoder.encode(assertion, StandardCharsets.UTF_8);
        Response response = http.exchange("POST", tokenUri,
                Map.of("Content-Type", "application/x-www-form-urlencoded"), form);
        if (!response.ok()) {
            throw new Bank2SheetsException("Google token request failed (HTTP " + response.status() + "): "
                    + response.body());
        }
        JsonObject result = JsonParser.parseString(response.body()).getAsJsonObject();
        if (!result.has("access_token")) {
            throw new Bank2SheetsException("Google token request returned no access_token: " + response.body());
        }
        accessToken = result.get("access_token").getAsString();
        return accessToken;
    }

    private JsonObject parseServiceAccount() {
        String json;
        try {
            json = new String(Base64.getDecoder().decode(config.serviceAccountB64()), StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) {
            throw new Bank2SheetsException("GOOGLE_SERVICE_ACCOUNT_B64 is not valid base64", e);
        }
        try {
            return JsonParser.parseString(json).getAsJsonObject();
        } catch (IllegalStateException | JsonSyntaxException e) {
            throw new Bank2SheetsException("GOOGLE_SERVICE_ACCOUNT_B64 is not a service account JSON object", e);
        }
    }

    private JsonObject readValues(String range, String token) {
        Response response = http.exchange("GET", valuesUrl(range),
                Map.of("Authorization", "Bearer " + token), null);
        if (!response.ok()) {
            throw new Bank2SheetsException("Google Sheets read failed (HTTP " + response.status() + "): "
                    + response.body());
        }
        return JsonParser.parseString(response.body()).getAsJsonObject();
    }

    private void appendValues(String token, String body) {
        Response response = http.exchange("POST", appendUrl(),
                Map.of("Authorization", "Bearer " + token, "Content-Type", "application/json"), body);
        if (!response.ok()) {
            throw new Bank2SheetsException("Google Sheets append failed (HTTP " + response.status() + "): "
                    + response.body());
        }
    }

    private String valuesUrl(String range) {
        // Spreadsheet ids and A1 ranges contain only URL-safe characters, so no
        // encoding: URLEncoder would turn the range colon into %3A.
        return API_BASE + "/" + config.spreadsheetId() + "/values/" + range;
    }

    private String appendUrl() {
        return valuesUrl(RANGE) + ":append?valueInputOption=" + VALUE_INPUT_OPTION;
    }
}
