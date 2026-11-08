package de.alexanderlindhorst.imapmcp.transport.http;

import com.nimbusds.jose.KeySourceException;

/**
 * The key decision cannot be made (SPEC 5.3 "Key availability", D16): the issuer's discovery or key endpoint is
 * down, the stale window has ended, or an unknown {@code kid} cannot be resolved. Not an invalid token: the
 * request gets {@code 503} with {@code Retry-After}, never a challenge. Carries no cause and no endpoint answer.
 */
public final class KeysUnavailableException extends KeySourceException {

    private final int retryAfterSeconds;

    public KeysUnavailableException(int retryAfterSeconds) {
        super("keys unavailable");
        this.retryAfterSeconds = retryAfterSeconds;
    }

    /** Seconds until a retry can help; the caller sends at least 1. */
    public int retryAfterSeconds() {
        return retryAfterSeconds;
    }
}
