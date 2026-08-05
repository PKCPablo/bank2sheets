package bank2sheets.model;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * One normalized bank transaction.
 *
 * <p>Amounts keep full precision as {@link BigDecimal}; the sheet sync phase
 * renders them as numeric cells. Provider payload variations are normalized
 * into this shape by the {@link bank2sheets.openbanking.TransactionSource}.
 */
public record Transaction(
        String id,
        String description,
        BigDecimal amount,
        String currency,
        LocalDate madeOn) {
}
