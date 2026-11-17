package bank2sheets.sheets;

import bank2sheets.Bank2SheetsException;

import bank2sheets.config.SheetsConfig;
import bank2sheets.http.HttpExchange;
import bank2sheets.http.HttpExchange.Response;
import bank2sheets.http.JsonSupport;
import bank2sheets.http.RealHttpExchange;
import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonSyntaxException;
import bank2sheets.http.Rs256Jwt;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.YearMonth;
import java.util.Base64;
import java.util.Comparator;
import java.util.HashMap;
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
 * <p>Target layout: one tab per month titled with the Spanish month name and
 * year (e.g. {@code Noviembre 2026}), columns A..D:
 * {@code Fecha, Importe, Descripción, Cuenta}. Rows are
 * appended with {@code valueInputOption=RAW}, so amounts sent as JSON numbers
 * become numeric cells and descriptive text stays literal (no date/formula
 * parsing; {@code Fecha} stays literal {@code DD/MM/YYYY} text); each append
 * batch is sorted by {@code Fecha} ascending (stable, so same-date rows keep
 * provider order). The header row is written once per tab when missing; a tab
 * whose existing header does not match the four expected cells (e.g. a legacy
 * six-column tab) fails fast with a reset instruction instead of being
 * migrated. Dedupe uses a hidden {@code _sync} helper tab
 * ({@code Tab | TransactionId}): ids are read once per client instance and a
 * transaction id is recorded there only after its visible row was appended
 * successfully, so a failed ledger append may duplicate rows on a later run but
 * never loses them.
 */
public final class GoogleSheetsClient {

    private static final String API_BASE = "https://sheets.googleapis.com/v4/spreadsheets";
    private static final String TOKEN_URI = "https://oauth2.googleapis.com/token";
    private static final String SCOPE = "https://www.googleapis.com/auth/spreadsheets";
    private static final String RANGE_SUFFIX = "!A1:D";
    private static final String HEADER_RANGE_SUFFIX = "!A1:D1";
    private static final String VALUE_INPUT_OPTION = "RAW";
    /** Hidden helper tab that records appended transaction ids for dedupe. */
    private static final String HELPER_TITLE = "_sync";
    /** Header row of the helper tab; data starts at row 2. */
    private static final List<Object> HELPER_HEADER = List.<Object>of("Tab", "TransactionId");
    private static final String HELPER_READ_RANGE_SUFFIX = "!A:B";
    private static final String HELPER_APPEND_RANGE_SUFFIX = "!A2:B";
    /** Header row; order must match {@link SheetRow#values()} (columns A..D). */
    private static final List<Object> HEADER_VALUES =
            List.<Object>of("Fecha", "Importe", "Descripción", "Cuenta");
    private static final Gson GSON = new Gson();
    /** Spanish month names for tab titles; index 0 = January. Locale-independent by design. */
    private static final List<String> SPANISH_MONTHS = List.of(
            "Enero", "Febrero", "Marzo", "Abril", "Mayo", "Junio",
            "Julio", "Agosto", "Septiembre", "Octubre", "Noviembre", "Diciembre");

    private final SheetsConfig config;
    private final HttpExchange http;
    private String accessToken;
    /** Titles of the spreadsheet tabs; null until the first metadata read. */
    private Set<String> sheetTitles;
    /** Appended transaction ids per tab title; null until the first ledger read. */
    private Map<String, Set<String>> ledger;

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

    /**
     * Returns the transaction ids already recorded for the month tab in the
     * hidden {@code _sync} ledger, as a mutable copy the caller can extend
     * with in-run ids.
     */
    public Set<String> existingTransactionIds(YearMonth month) {
        String sheet = tabTitle(month);
        ensureSheet(sheet);
        return new HashSet<>(ledger().getOrDefault(sheet, Set.of()));
    }

    /**
     * Appends the given rows to the month tab in one batch after the header,
     * sorted by {@code Fecha} ascending (stable sort: same-date rows keep input
     * order; the caller's list is not mutated). When the tab has no header yet,
     * the header row is written first so a fresh tab gets the expected columns.
     * A tab whose existing header does not match the four expected cells (e.g.
     * a legacy six-column tab) fails fast with a reset instruction; nothing is
     * appended to it. Empty input does nothing (no HTTP calls). After the
     * visible append succeeds, one {@code [Tab, TransactionId]} pair per row is
     * appended to the hidden {@code _sync} ledger and only then added to the
     * in-memory cache; a failed ledger append throws before the cache is
     * updated.
     */
    public void appendRows(YearMonth month, List<SheetRow> rows) {
        if (rows.isEmpty()) {
            return;
        }
        String sheet = tabTitle(month);
        ensureSheet(sheet);
        ledger();
        String sheetPrefix = quoted(sheet);
        String token = accessToken();
        JsonArray headerRow = readHeaderRow(token, sheetPrefix + HEADER_RANGE_SUFFIX);
        if (headerRow == null) {
            appendValues(token, sheetPrefix + RANGE_SUFFIX, toValuesJson(List.of(HEADER_VALUES)));
        } else {
            for (int i = 0; i < HEADER_VALUES.size(); i++) {
                if (!HEADER_VALUES.get(i).equals(stringAt(headerRow, i))) {
                    throw new Bank2SheetsException("Tab '" + sheet + "' has an unexpected header; this version "
                            + "writes the four-column layout " + HEADER_VALUES + ". Delete or archive that tab "
                            + "and the '_sync' tab (or use a fresh spreadsheet), then run sync again.");
                }
            }
        }
        List<SheetRow> sorted = rows.stream()
                .sorted(Comparator.comparing(SheetRow::fecha))
                .toList();
        appendValues(token, sheetPrefix + RANGE_SUFFIX,
                toValuesJson(sorted.stream().map(SheetRow::values).toList()));
        appendValues(token, quoted(HELPER_TITLE) + HELPER_APPEND_RANGE_SUFFIX,
                toValuesJson(sorted.stream()
                        .map(row -> List.<Object>of(sheet, row.transactionId()))
                        .toList()));
        ledger.computeIfAbsent(sheet, key -> new HashSet<>())
                .addAll(sorted.stream().map(SheetRow::transactionId).toList());
    }

    /**
     * Ensures a tab titled {@code title} exists: on first use, loads the
     * spreadsheet's tab titles once ({@code ?fields=sheets.properties.title});
     * on a miss, creates the tab with a single {@code addSheet} batchUpdate and
     * caches it for the rest of the run.
     */
    private void ensureSheet(String title) {
        ensureSheetTitles();
        if (!sheetTitles.contains(title)) {
            createSheet(title, false);
        }
    }

    /** Loads the spreadsheet's tab titles once per client instance. */
    private void ensureSheetTitles() {
        if (sheetTitles != null) {
            return;
        }
        Response response = http.exchange("GET",
                API_BASE + "/" + config.spreadsheetId() + "?fields=sheets.properties.title",
                Map.of("Authorization", "Bearer " + accessToken()), null);
        requireOk(response, "metadata");
        JsonObject spreadsheet = JsonSupport.parseObject(response.body());
        Set<String> loaded = new HashSet<>();
        if (spreadsheet.has("sheets")) {
            for (JsonElement sheet : spreadsheet.getAsJsonArray("sheets")) {
                JsonObject properties = sheet.getAsJsonObject().getAsJsonObject("properties");
                if (properties != null && properties.has("title")) {
                    loaded.add(properties.get("title").getAsString());
                }
            }
        }
        sheetTitles = loaded;
    }

    /** Creates a tab via one {@code addSheet} batchUpdate; {@code hidden} adds {@code "hidden": true}. */
    private void createSheet(String title, boolean hidden) {
        JsonObject properties = new JsonObject();
        properties.addProperty("title", title);
        if (hidden) {
            properties.addProperty("hidden", true);
        }
        JsonObject addSheet = new JsonObject();
        addSheet.add("properties", properties);
        JsonObject body = new JsonObject();
        JsonArray requests = new JsonArray();
        JsonObject wrapper = new JsonObject();
        wrapper.add("addSheet", addSheet);
        requests.add(wrapper);
        body.add("requests", requests);
        Response response = http.exchange("POST",
                API_BASE + "/" + config.spreadsheetId() + ":batchUpdate",
                Map.of("Authorization", "Bearer " + accessToken(), "Content-Type", "application/json"),
                body.toString());
        requireOk(response, "add sheet");
        sheetTitles.add(title);
    }

    /** Ensures the hidden {@code _sync} ledger tab exists, writing its header row when created. */
    private void ensureHelperSheet() {
        ensureSheetTitles();
        if (!sheetTitles.contains(HELPER_TITLE)) {
            createSheet(HELPER_TITLE, true);
            appendValues(accessToken(), quoted(HELPER_TITLE) + "!A1:B",
                    toValuesJson(List.of(HELPER_HEADER)));
        }
    }

    /**
     * Loads the ledger ({@code tab title -> ids}) from one read of
     * {@code '_sync'!A:B}; blank rows and the {@code Tab} header row are skipped.
     */
    private Map<String, Set<String>> ledger() {
        if (ledger == null) {
            ensureHelperSheet();
            Map<String, Set<String>> loaded = new HashMap<>();
            JsonObject result = readValues(quoted(HELPER_TITLE) + HELPER_READ_RANGE_SUFFIX, accessToken());
            JsonArray values = result.has("values") ? result.getAsJsonArray("values") : new JsonArray();
            for (JsonElement row : values) {
                JsonArray cells = row.getAsJsonArray();
                String tab = stringAt(cells, 0);
                String id = stringAt(cells, 1);
                if (tab.isBlank() || id.isBlank() || HELPER_HEADER.get(0).equals(tab)) {
                    continue;
                }
                loaded.computeIfAbsent(tab, key -> new HashSet<>()).add(id);
            }
            ledger = loaded;
        }
        return ledger;
    }

    /** Serializes rows to the append body: {@code {"values": [[...]]}} with amounts as JSON numbers. */
    private static String toValuesJson(List<List<Object>> rows) {
        return GSON.toJson(Map.of("values", rows));
    }

    /** First row of the tab's header range, or {@code null} when the tab is empty. */
    private JsonArray readHeaderRow(String token, String range) {
        JsonObject result = readValues(range, token);
        if (!result.has("values")) {
            return null;
        }
        JsonArray values = result.getAsJsonArray("values");
        return values.isEmpty() ? null : values.get(0).getAsJsonArray();
    }

    /** Cell text at {@code index}, or "" when the cell is missing or JSON null. */
    private static String stringAt(JsonArray row, int index) {
        if (index >= row.size() || row.get(index).isJsonNull()) {
            return "";
        }
        return row.get(index).getAsString();
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
        String email = JsonSupport.stringOrEmpty(serviceAccount, "client_email");
        String privateKey = JsonSupport.stringOrEmpty(serviceAccount, "private_key");
        if (email.isBlank() || privateKey.isBlank()) {
            throw new Bank2SheetsException("GOOGLE_SERVICE_ACCOUNT_B64 is not a service account JSON "
                    + "(missing client_email/private_key)");
        }
        String tokenUri = JsonSupport.stringOrEmpty(serviceAccount, "token_uri");
        if (tokenUri.isBlank()) {
            tokenUri = TOKEN_URI;
        }

        JsonObject header = new JsonObject();
        header.addProperty("alg", "RS256");
        header.addProperty("typ", "JWT");
        long now = Instant.now().getEpochSecond();
        JsonObject claims = new JsonObject();
        claims.addProperty("iss", email);
        claims.addProperty("scope", SCOPE);
        claims.addProperty("aud", tokenUri);
        claims.addProperty("iat", now);
        claims.addProperty("exp", now + 3600);
        String assertion = Rs256Jwt.encode(header.toString(), claims.toString(), privateKey);

        String form = "grant_type="
                + URLEncoder.encode("urn:ietf:params:oauth:grant-type:jwt-bearer", StandardCharsets.UTF_8)
                + "&assertion=" + URLEncoder.encode(assertion, StandardCharsets.UTF_8);
        Response response = http.exchange("POST", tokenUri,
                Map.of("Content-Type", "application/x-www-form-urlencoded"), form);
        if (!response.ok()) {
            throw new Bank2SheetsException("Google token request failed (HTTP " + response.status() + "): "
                    + response.body());
        }
        JsonObject result = JsonSupport.parseObject(response.body());
        if (JsonSupport.stringOrEmpty(result, "access_token").isBlank()) {
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
            return JsonSupport.parseObject(json);
        } catch (IllegalStateException | JsonSyntaxException e) {
            throw new Bank2SheetsException("GOOGLE_SERVICE_ACCOUNT_B64 is not a service account JSON object", e);
        }
    }

    /** Throws a descriptive {@link Bank2SheetsException} when a Sheets API call did not return 2xx. */
    private static void requireOk(Response response, String action) {
        if (!response.ok()) {
            throw new Bank2SheetsException("Google Sheets " + action + " failed (HTTP " + response.status()
                    + "): " + response.body());
        }
    }

    private JsonObject readValues(String range, String token) {
        Response response = http.exchange("GET", valuesUrl(range),
                Map.of("Authorization", "Bearer " + token), null);
        requireOk(response, "read");
        return JsonSupport.parseObject(response.body());
    }

    private void appendValues(String token, String range, String body) {
        Response response = http.exchange("POST", appendUrl(range),
                Map.of("Authorization", "Bearer " + token, "Content-Type", "application/json"), body);
        requireOk(response, "append");
    }

    private String valuesUrl(String range) {
        // The range is pre-encoded by quoted(): spaces become %20 while the
        // apostrophe, ! and : stay literal (legal URI path characters). Do not use
        // URLEncoder: it would turn the range colon into %3A and spaces into +.
        return API_BASE + "/" + config.spreadsheetId() + "/values/" + range;
    }

    private String appendUrl(String range) {
        return valuesUrl(range) + ":append?valueInputOption=" + VALUE_INPUT_OPTION;
    }

    /** Quotes a tab title for use in an A1 range: {@code 'Agosto%202026'!A1:D}. */
    private static String quoted(String sheet) {
        return "'" + sheet.replace(" ", "%20") + "'";
    }

    /** Tab title for a month: Spanish month name, space, four-digit year (e.g. {@code Agosto 2026}). */
    private static String tabTitle(YearMonth month) {
        return SPANISH_MONTHS.get(month.getMonthValue() - 1) + " " + month.getYear();
    }
}
