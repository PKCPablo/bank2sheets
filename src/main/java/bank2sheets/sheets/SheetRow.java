package bank2sheets.sheets;

import java.math.BigDecimal;
import java.util.List;

/**
 * One spreadsheet row to append, in the fixed column order
 * Fecha, Descripción, Importe, Moneda, TransactionId.
 *
 * <p>Importe keeps full precision as {@link BigDecimal} and is serialized as a
 * JSON number (a numeric cell), never as a string. TransactionId is the dedupe
 * key used by the sync pipeline.
 */
public record SheetRow(
        String fecha,
        String descripcion,
        BigDecimal importe,
        String moneda,
        String transactionId) {

    /** Column order; matches the spreadsheet header written by {@link GoogleSheetsClient}. */
    List<Object> values() {
        return List.<Object>of(fecha, descripcion, importe, moneda, transactionId);
    }
}
