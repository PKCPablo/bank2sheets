package bank2sheets.sheets;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * One spreadsheet row to append, in the fixed column order
 * Fecha, Movimientos, Descripción, Cuenta.
 *
 * <p>The amount (visible column `Movimientos`) keeps full precision as {@link BigDecimal} and is serialized as a
 * JSON number (a numeric cell), never as a string. Fecha is formatted as ISO
 * {@code yyyy-MM-dd} at write time; the visible row append uses
 * {@code valueInputOption=USER_ENTERED}, so Sheets parses it into a date-typed
 * cell whose display follows the spreadsheet locale. Ordering uses the
 * underlying {@link LocalDate}. Descripción and Cuenta are apostrophe-escaped
 * so formula-like or number/date-like text stays literal (Sheets hides the
 * marker apostrophe). TransactionId is the internal dedupe value recorded in
 * the hidden {@code _sync} ledger; it is not written to the visible row. Cuenta
 * labels the source account (display name when available, otherwise the
 * provider account id).
 */
public record SheetRow(
        LocalDate fecha,
        BigDecimal importe,
        String descripcion,
        String cuenta,
        String transactionId) {

    /** ISO local date format (e.g. {@code 2026-08-01}); parsed by USER_ENTERED into a date cell. */
    private static final DateTimeFormatter FECHA_FORMAT = DateTimeFormatter.ISO_LOCAL_DATE;

    /** Column order; matches the spreadsheet header written by {@link GoogleSheetsClient} (must match HEADER_VALUES). */
    List<Object> values() {
        return List.<Object>of(fecha.format(FECHA_FORMAT), importe, literal(descripcion), literal(cuenta));
    }

    /** Prefixes a leading apostrophe so Sheets stores the value as literal text (marker is not displayed). */
    private static String literal(String value) {
        return value.isEmpty() ? value : "'" + value;
    }
}
