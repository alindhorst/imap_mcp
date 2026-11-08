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

    default void timeout(TenantId tenant) { }

    /** A stored credential failed to decrypt (integrity failure, not a key-service outage). */
    default void decryptFailure(TenantId tenant) { }

    /** The key service (for example Vault Transit) was unreachable while unwrapping a credential key. */
    default void keyServiceUnavailable(TenantId tenant) { }

    /** A request was answered {@code 503} because the issuer's signing keys are unavailable (D16); replica-wide. */
    default void oauthKeysUnavailable() { }

    /** An {@code initialize} was refused by the per-principal rate limit (SPEC 4, D12). */
    default void initializeRateLimited(TenantId tenant) { }
}
