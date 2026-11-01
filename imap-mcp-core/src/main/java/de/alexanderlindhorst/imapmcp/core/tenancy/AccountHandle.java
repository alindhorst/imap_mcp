package de.alexanderlindhorst.imapmcp.core.tenancy;

import java.util.Objects;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Opaque client-facing account reference ({@code acc_...}). This type only fixes the format; derivation is
 * defined elsewhere. Untrusted input must go through {@link #parse(String)}.
 */
public record AccountHandle(String value) {

    private static final Pattern FORMAT = Pattern.compile("acc_[A-Za-z0-9_-]{1,128}");

    public AccountHandle {
        Objects.requireNonNull(value, "value");
        if (!FORMAT.matcher(value).matches()) {
            throw new IllegalArgumentException("malformed account handle");
        }
    }

    /** Lenient parse of client input: empty instead of an exception when malformed. */
    public static Optional<AccountHandle> parse(String raw) {
        if (raw == null || !FORMAT.matcher(raw).matches()) {
            return Optional.empty();
        }
        return Optional.of(new AccountHandle(raw));
    }
}
