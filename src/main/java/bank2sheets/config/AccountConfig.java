package bank2sheets.config;

/**
 * One configured account: which provider supplies it and the provider-side
 * account id to fetch. The supported provider is {@code enablebanking} (any
 * ASPSP via Enable Banking, with BBVA Spain as the default/validated target);
 * unknown providers fail fast in {@link AppConfig}.
 */
public record AccountConfig(String provider, String providerAccountId) {
}
