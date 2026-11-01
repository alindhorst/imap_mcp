package de.alexanderlindhorst.imapmcp.core.account;

import java.util.Objects;

/** Opaque reference to a secret (for example {@code env:NAME}); parsing belongs to the store. Not the secret. */
public record SecretRef(String value) {

    public SecretRef {
        Objects.requireNonNull(value, "value");
    }

    @Override
    public String toString() {
        return "SecretRef[***]";
    }
}
