package bank2sheets.sheets;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * One spreadsheet row to append, in the fixed column order
 * Fecha, Importe, Descripción, Cuenta.
 *
 * <p>Importe keeps full precision as {@link BigDecimal} and is serialized as a
 * JSON number (a numeric cell), never as a string. Fecha is formatted as
 * {@code DD/MM/YYYY} text at write time; ordering uses the underlying
 * {@link LocalDate}. TransactionId is the internal dedupe value recorded in the
 * hidden {@code _sync} ledger; it is not written to the visible row. Cuenta
 * labels the source account (display name when available, otherwise the
 * provider account id).
 */
public record SheetRow(
        LocalDate fecha,
        BigDecimal importe,
        String descripcion,
        String cuenta,
        String transactionId) {

    /** Visible date format: day, month, four-digit year (e.g. {@code 01/08/2026}). */
    private static final DateTimeFormatter FECHA_FORMAT = DateTimeFormatter.ofPattern("dd/MM/uuuu");

    /** Column order; matches the spreadsheet header written by {@link GoogleSheetsClient} (must match HEADER_VALUES). */
    List<Object> values() {
        return List.<Object>of(fecha.format(FECHA_FORMAT), importe, descripcion, cuenta);
    }
}
