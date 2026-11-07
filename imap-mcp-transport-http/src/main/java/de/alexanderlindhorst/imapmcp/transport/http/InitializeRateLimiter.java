package de.alexanderlindhorst.imapmcp.transport.http;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.Objects;

/**
 * Token bucket per principal for {@code initialize} requests of the stateful transport (SPEC 4, D12): refilled at
 * {@code ratePerMinute} tokens per minute up to a capacity of twice that, per replica, in memory. A new principal
 * starts with a full bucket. An entry is dropped once its bucket is full again (a missing entry equals a full
 * bucket), and the table holds at most {@code maxPrincipals} entries: when it is full, refilled buckets are dropped
 * first, and only if it is still full of live entries a principal it does not know is refused (fail-closed), so
 * flooding with new principals neither grows the table nor resets anyone's budget. Credit is kept in integer
 * nanoseconds so the {@code Retry-After} values are exact.
 */
final class InitializeRateLimiter {

    static final int MAX_PRINCIPALS = 10_000;
    private static final long NANOS_PER_MINUTE = Duration.ofMinutes(1).toNanos();
    private static final Duration SWEEP_INTERVAL = Duration.ofSeconds(1);

    private static final class Bucket {
        long credit; // nanoseconds of refill credit; a token costs costNanos
        Instant updated;
    }

    private final Clock clock;
    private final long costNanos;
    private final long capacityNanos;
    private final long retryAtFullTable;
    private final int maxPrincipals;
    private final Map<String, Bucket> buckets = new HashMap<>();
    private Instant lastSweep;

    InitializeRateLimiter(Clock clock, int ratePerMinute) {
        this(clock, ratePerMinute, MAX_PRINCIPALS);
    }

    InitializeRateLimiter(Clock clock, int ratePerMinute, int maxPrincipals) {
        this.clock = Objects.requireNonNull(clock, "clock");
        if (ratePerMinute < 1 || ratePerMinute > 600 || maxPrincipals < 1) {
            throw new IllegalArgumentException("ratePerMinute must be 1 to 600 and maxPrincipals positive");
        }
        this.costNanos = NANOS_PER_MINUTE / ratePerMinute;
        this.capacityNanos = costNanos * 2L * ratePerMinute;
        this.retryAtFullTable = wholeSeconds(costNanos);
        this.maxPrincipals = maxPrincipals;
        this.lastSweep = clock.instant();
    }

    /**
     * Takes one token for the principal. Returns 0 when admitted, otherwise the whole seconds (at least 1) until a
     * token is available; a refusal takes nothing and creates no entry.
     */
    synchronized long tryAcquire(String principal) {
        Instant now = clock.instant();
        if (!now.isBefore(lastSweep.plus(SWEEP_INTERVAL))) {
            sweep(now);
        }
        Bucket bucket = buckets.get(principal);
        if (bucket == null) {
            if (buckets.size() >= maxPrincipals) {
                sweep(now);
                if (buckets.size() >= maxPrincipals) {
                    return retryAtFullTable; // full of live entries: refuse a newcomer (fail-closed)
                }
            }
            bucket = new Bucket();
            bucket.credit = capacityNanos;
            bucket.updated = now;
            buckets.put(principal, bucket);
        } else {
            refill(bucket, now);
        }
        if (bucket.credit < costNanos) {
            return wholeSeconds(costNanos - bucket.credit);
        }
        bucket.credit -= costNanos;
        return 0;
    }

    private void refill(Bucket bucket, Instant now) {
        long elapsed = Duration.between(bucket.updated, now).toNanos();
        if (elapsed > 0) {
            bucket.credit = Math.min(capacityNanos, bucket.credit + Math.min(elapsed, capacityNanos));
            bucket.updated = now;
        }
    }

    private void sweep(Instant now) {
        lastSweep = now;
        for (Iterator<Bucket> it = buckets.values().iterator(); it.hasNext();) {
            Bucket b = it.next();
            refill(b, now);
            if (b.credit >= capacityNanos) {
                it.remove();
            }
        }
    }

    private static long wholeSeconds(long nanos) {
        return Math.max(1, (nanos + 999_999_999L) / 1_000_000_000L);
    }

    synchronized int trackedPrincipals() {
        return buckets.size();
    }
}
