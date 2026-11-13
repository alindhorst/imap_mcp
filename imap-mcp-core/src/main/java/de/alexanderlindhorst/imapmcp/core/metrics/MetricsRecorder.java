package de.alexanderlindhorst.imapmcp.core.metrics;

import de.alexanderlindhorst.imapmcp.core.account.PoolKey;
import de.alexanderlindhorst.imapmcp.core.tenancy.TenantId;
import java.time.Duration;
import java.time.Instant;

/**
 * Recording side of the internal metrics (SPEC 5.6). Implementations must be thread-safe, non-blocking and must
 * never throw; callers do not guard against failures. Everything is attributed to a tenant or an account of a
 * tenant; implementations expose only hashed ids. A Micrometer-backed implementation can be added later behind
 * this interface.
 */
public interface MetricsRecorder {

    /** Recorder that discards everything. */
    MetricsRecorder NOOP = new MetricsRecorder() { };

    /** Time source for callers that measure latency, so tests can drive it with a fake clock. */
    default Instant now() {
        return Instant.now();
    }

    /** A lease was handed out; {@code wait} is the time from the request to the usable Store (borrow incl. connect). */
    default void leaseAcquired(PoolKey key, Duration wait) { }

    /** One completed IMAP operation of a tool (for example tool {@code search_messages}, op {@code search}). */
    default void operation(TenantId tenant, String tool, String op, Duration latency) { }

    default void authFailure(TenantId tenant) { }

    /** Admission refused the call (SERVER_BUSY); {@code scope} is the lower-case scope name (global, tenant, mailbox). */
    default void busyRejection(PoolKey key, String scope) { }

    /** A mutating call failed check-order step 1 and wrote no audit row (SPEC 5.3 D10, 5.6). */
    default void writeArgumentRejected(TenantId tenant, String tool) { }

    /** A mutating call of a principal without a tenant row ended in a failure; replica-wide, per tool (D11). */
    default void writeWithoutTenant(String tool) { }

    default void timeout(TenantId tenant) { }

    /** A stored credential failed to decrypt (integrity failure, not a key-service outage). */
    default void decryptFailure(TenantId tenant) { }

    /** The key service (for example Vault Transit) was unreachable while unwrapping a credential key. */
    default void keyServiceUnavailable(TenantId tenant) { }

    /** A request was answered {@code 503} because the issuer's signing keys are unavailable (D16); replica-wide. */
    default void oauthKeysUnavailable() { }

    /** A key decision was made with the stale key set of an outage (D16, counts key decisions, not validated tokens); replica-wide. */
    default void oauthKeysStale() { }

    /** One upstream token refresh ended; {@code result} is ok, invalid_grant, unavailable, client_rejected or lock_timeout (SPEC 5.5, 5.6). */
    default void oauthRefresh(TenantId tenant, String result) { }

    /** A commit failed after the provider rotated the refresh token: the grant is lost and a re-link is needed (SPEC 5.5, 5.6). */
    default void oauthGrantLost(TenantId tenant) { }

    /** An {@code initialize} was refused by the per-principal rate limit (SPEC 4, D12). */
    default void initializeRateLimited(TenantId tenant) { }
}
