package bank2sheets.model;

/**
 * A bank account exposed by a provider (for example one account of the BBVA
 * connection opened through Salt Edge). The {@code id} is the provider-side
 * account id; the name is a provider-provided display name.
 */
public record Account(String id, String name) {
}
