package de.alexanderlindhorst.imapmcp.core.linking;

import de.alexanderlindhorst.imapmcp.core.tenancy.TenantId;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

/**
 * At most {@code limit} link probes per tenant in a sliding window (SPEC 5.3: 10 per hour), counted per replica in
 * memory. It tracks at most {@code maxTenants} tenants with a live window; only expired entries are dropped, and when
 * all slots are live a tenant it does not know yet is refused (fail-closed), so flooding cannot reset a limit.
 */
public final class ProbeRateLimiter {

    private final Clock clock;
    private final int limit;
    private final Duration window;
    private final int maxTenants;
    private final Map<TenantId, Deque<Instant>> byTenant = new HashMap<>();

    public ProbeRateLimiter(Clock clock, int limit, Duration window, int maxTenants) {
        this.clock = Objects.requireNonNull(clock, "clock");
        if (limit < 1 || maxTenants < 1 || window.isNegative() || window.isZero()) {
            throw new IllegalArgumentException("limit, window and maxTenants must be positive");
        }
        this.limit = limit;
        this.window = window;
        this.maxTenants = maxTenants;
    }

    /** Takes one probe for the tenant if the window has room. */
    public synchronized boolean tryAcquire(TenantId tenant) {
        Instant now = clock.instant();
        Instant cutoff = now.minus(window);
        byTenant.values().removeIf(q -> q.isEmpty() || !q.peekLast().isAfter(cutoff));
        Deque<Instant> q = byTenant.get(tenant);
        if (q == null) {
            if (byTenant.size() >= maxTenants) {
                return false; // full of live entries: refuse rather than forget anyone's budget (fail-closed)
            }
            q = new ArrayDeque<>();
            byTenant.put(tenant, q);
        }
        while (!q.isEmpty() && !q.peekFirst().isAfter(cutoff)) {
            q.pollFirst();
        }
        if (q.size() >= limit) {
            return false;
        }
        q.addLast(now);
        return true;
    }

    synchronized int trackedTenants() {
        return byTenant.size();
    }
}
