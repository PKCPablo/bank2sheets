package bank2sheets.config;

/**
 * One configured account: which provider supplies it and the provider-side
 * account id to fetch. The supported provider is {@code enablebanking} (BBVA
 * via Enable Banking); unknown providers fail fast in {@link AppConfig}.
 */
public record AccountConfig(String provider, String providerAccountId) {
}
