package bank2sheets.config;

/**
 * One configured account: which provider supplies it, the provider-side
 * account id to fetch, and an optional {@code Cuenta} label used in the
 * spreadsheet rows. The supported provider is {@code enablebanking} (any
 * ASPSP via Enable Banking, with BBVA Spain as the default/validated target);
 * unknown providers fail fast in {@link AppConfig}.
 *
 * <p>When the label is blank, {@code sync}/{@code fetch} fall back to the bank
 * display name (via {@code listAccounts()}) or the provider account id.
 */
public record AccountConfig(String provider, String providerAccountId, String label) {

    public AccountConfig {
        label = label == null ? "" : label.trim();
    }

    /** Convenience constructor for unlabelled accounts (label defaults to empty). */
    public AccountConfig(String provider, String providerAccountId) {
        this(provider, providerAccountId, "");
    }
}
