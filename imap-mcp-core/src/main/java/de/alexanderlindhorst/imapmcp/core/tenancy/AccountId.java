package de.alexanderlindhorst.imapmcp.core.tenancy;

import java.util.Objects;
import java.util.regex.Pattern;

/** Config id of an account, unique within a tenant. Never sent to clients (they get an {@link AccountHandle}). */
public record AccountId(String value) {

    private static final Pattern FORMAT = Pattern.compile("[a-z0-9-]{1,32}");

    public AccountId {
        Objects.requireNonNull(value, "value");
        if (!FORMAT.matcher(value).matches()) {
            throw new IllegalArgumentException("account id must match [a-z0-9-]{1,32}");
        }
    }
}
