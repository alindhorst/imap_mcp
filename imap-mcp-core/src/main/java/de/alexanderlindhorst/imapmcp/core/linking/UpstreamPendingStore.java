package de.alexanderlindhorst.imapmcp.core.linking;

import de.alexanderlindhorst.imapmcp.core.tenancy.AccountId;
import de.alexanderlindhorst.imapmcp.core.tenancy.TenantId;
import java.time.Duration;
import java.util.Optional;

/**
 * Upstream OAuth logins in flight (SPEC 5.3, 5.7 {@code upstream_pending}): the {@code state} is the key (stored as
 * SHA-256 only) and each entry is consumed at most once. The tenant is the one of the web session that started the
 * flow; the callback takes it from here and never from the request.
 */
public interface UpstreamPendingStore {

    Duration TTL = Duration.ofMinutes(10);

    /** At most this many unexpired entries per tenant. */
    int MAX_PER_TENANT = 3;

    /**
     * The draft of the account and the PKCE verifier. {@code relinkAccount} names the account of a re-link, else
     * null. {@code toString} shows no field.
     */
    record Pending(TenantId tenant, String providerId, String pkceVerifier, String label, String address,
            boolean readOnly, AccountId relinkAccount) {

        @Override
        public String toString() {
            return "Pending[***]";
        }
    }

    /** The tenant has {@link #MAX_PER_TENANT} unexpired entries already. */
    class TenantLimitReachedException extends RuntimeException {

        public TenantLimitReachedException() {
            super("too many pending upstream logins for the tenant", null, false, false);
        }
    }

    /** The table is full of unexpired entries (the cap of {@code oidc_pending}). */
    class LimitReachedException extends RuntimeException {

        public LimitReachedException() {
            super("too many pending upstream logins", null, false, false);
        }
    }

    /** Stores the entry under a fresh random state and returns the state. */
    String begin(Pending pending);

    /** Atomically removes and returns the entry if it exists and has not expired; empty otherwise. */
    Optional<Pending> consume(String state);
}
