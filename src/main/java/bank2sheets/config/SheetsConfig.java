package bank2sheets.config;

import java.util.Map;

/**
 * Runtime configuration for the Google Sheets sync, read from environment
 * variables at runtime and never stored in the repository:
 * <ul>
 *   <li>{@code GOOGLE_SERVICE_ACCOUNT_B64} - base64-encoded service account JSON (never committed)</li>
 *   <li>{@code GOOGLE_SPREADSHEET_ID} - id of the target spreadsheet, shared with the service account</li>
 * </ul>
 */
public record SheetsConfig(String serviceAccountB64, String spreadsheetId) {

    public static SheetsConfig fromEnv() {
        return fromEnv(System.getenv());
    }

    static SheetsConfig fromEnv(Map<String, String> env) {
        return new SheetsConfig(
                env.getOrDefault("GOOGLE_SERVICE_ACCOUNT_B64", ""),
                env.getOrDefault("GOOGLE_SPREADSHEET_ID", ""));
    }
}
