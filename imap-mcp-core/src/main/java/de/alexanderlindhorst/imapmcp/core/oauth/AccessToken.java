package de.alexanderlindhorst.imapmcp.core.oauth;

import de.alexanderlindhorst.imapmcp.core.account.Secret;
import java.time.Instant;
import java.util.Objects;

/**
 * An upstream OAuth access token (SPEC 5.5). Held in memory only, never persisted or logged; {@link #close()} wipes
 * the value. The cache shares one instance among concurrent callers and does not wipe it on replacement (a caller
 * may still be connecting with it), so only a holder that owns a private copy should call it.
 */
public record AccessToken(Secret value, Instant expiresAt) implements AutoCloseable {

    public AccessToken {
        Objects.requireNonNull(value, "value");
        Objects.requireNonNull(expiresAt, "expiresAt");
    }

    @Override
    public void close() {
        value.close();
    }

    @Override
    public String toString() {
        return "AccessToken[***, expiresAt=" + expiresAt + "]";
    }
}
