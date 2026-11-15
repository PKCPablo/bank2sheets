package bank2sheets.model;

/**
 * A bank account exposed by a provider (one account of an ASPSP connection
 * opened through Enable Banking; BBVA Spain is the default/validated target).
 * The {@code id} is the provider-side account id; the name is a
 * provider-provided display name.
 */
public record Account(String id, String name) {
}
