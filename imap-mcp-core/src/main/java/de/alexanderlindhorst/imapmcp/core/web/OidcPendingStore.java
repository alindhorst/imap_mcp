package de.alexanderlindhorst.imapmcp.core.web;

import java.time.Duration;
import java.util.Optional;

/**
 * Logins in flight at the identity provider (SPEC 5.7 {@code oidc_pending}): the {@code state} is the key (stored
 * as SHA-256 only), with the {@code nonce} and PKCE verifier it guards. Each entry is consumed at most once.
 */
public interface OidcPendingStore {

    Duration TTL = Duration.ofMinutes(10);

    /** Nonce and PKCE verifier of a pending login. */
    record Pending(String nonce, String pkceVerifier) {

        @Override
        public String toString() {
            return "Pending[***]";
        }
    }

    /** The store is full: too many unfinished logins. The caller answers 503. */
    class LimitReachedException extends RuntimeException {

        public LimitReachedException() {
            super("too many pending logins");
        }
    }

    /** Stores the pair under a fresh random state and returns the state. */
    String begin(String nonce, String pkceVerifier);

    /** Atomically removes and returns the entry if it exists and has not expired; empty otherwise (replay, unknown). */
    Optional<Pending> consume(String state);
}
