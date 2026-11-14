package bank2sheets.config;

import java.util.Map;

/**
 * Runtime configuration for the Google Sheets sync, read from environment
 * variables at runtime and never stored in the repository; the variable names
 * are documented in the README configuration table. The service account JSON is
 * base64-encoded and shared with the target spreadsheet.
 */
public record SheetsConfig(String serviceAccountB64, String spreadsheetId) {

    public static SheetsConfig fromEnv() {
        Map<String, String> env = System.getenv();
        return new SheetsConfig(
                env.getOrDefault("GOOGLE_SERVICE_ACCOUNT_B64", ""),
                env.getOrDefault("GOOGLE_SPREADSHEET_ID", ""));
    }
}
