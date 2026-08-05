package bank2sheets.config;

/**
 * One configured account: which provider supplies it and the provider-side
 * account id to fetch. v1 only supports {@code provider == "saltedge"}
 * (BBVA via Salt Edge); unknown providers fail fast in {@link AppConfig}.
 */
public record AccountConfig(String provider, String providerAccountId) {
}
