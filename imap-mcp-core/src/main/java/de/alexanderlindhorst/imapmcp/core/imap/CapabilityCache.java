package de.alexanderlindhorst.imapmcp.core.imap;

import de.alexanderlindhorst.imapmcp.core.account.PoolKey;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import de.alexanderlindhorst.imapmcp.core.util.ExpiringLruMap;
import java.util.Objects;

/**
 * Capabilities per {@code (tenant, account)} with a TTL (SPEC 5.4). Keys are internal {@link PoolKey}s only.
 * Populated by the pool factory on connect; {@link #get} distinguishes fresh, expired and missing so that
 * callers know whether to probe again. Bounded (SPEC 5.5): an entry not used for {@link #IDLE_EXPIRY} is dropped
 * and at most {@link #MAX_ENTRIES} are kept, least recently used out.
 */
public final class CapabilityCache {

    /** Default TTL (user decision: 10 minutes); configured as {@code pool.capabilityTtl}. */
    public static final Duration DEFAULT_TTL = Duration.ofMinutes(10);

    public static final Duration IDLE_EXPIRY = Duration.ofHours(24);
    public static final int MAX_ENTRIES = 10_000;

    /** Result of a lookup. */
    public sealed interface Lookup {
        record Fresh(ImapCapabilities capabilities, Instant detectedAt) implements Lookup { }

        record Expired(ImapCapabilities capabilities, Instant detectedAt) implements Lookup { }

        record Missing() implements Lookup { }
    }

    private record Stamped(ImapCapabilities capabilities, Instant detectedAt) { }

    private final ExpiringLruMap<PoolKey, Stamped> entries;
    private final Duration ttl;
    private final Clock clock;

    public CapabilityCache() {
        this(DEFAULT_TTL, Clock.systemUTC());
    }

    public CapabilityCache(Clock clock) {
        this(DEFAULT_TTL, clock);
    }

    public CapabilityCache(Duration ttl, Clock clock) {
        this(ttl, clock, MAX_ENTRIES);
    }

    CapabilityCache(Duration ttl, Clock clock, int maxEntries) {
        this.ttl = Objects.requireNonNull(ttl, "ttl");
        this.clock = Objects.requireNonNull(clock, "clock");
        if (ttl.isNegative() || ttl.isZero()) {
            throw new IllegalArgumentException("ttl must be positive");
        }
        this.entries = new ExpiringLruMap<>(maxEntries, IDLE_EXPIRY, clock);
    }

    public void put(PoolKey key, ImapCapabilities capabilities) {
        entries.put(Objects.requireNonNull(key, "key"),
                new Stamped(Objects.requireNonNull(capabilities, "capabilities"), clock.instant()));
    }

    public Lookup get(PoolKey key) {
        Stamped s = entries.get(Objects.requireNonNull(key, "key"));
        if (s == null) {
            return new Lookup.Missing();
        }
        boolean fresh = Duration.between(s.detectedAt(), clock.instant()).compareTo(ttl) < 0;
        return fresh ? new Lookup.Fresh(s.capabilities(), s.detectedAt())
                : new Lookup.Expired(s.capabilities(), s.detectedAt());
    }

    public void invalidate(PoolKey key) {
        entries.remove(Objects.requireNonNull(key, "key"));
    }

    int size() {
        return entries.size();
    }
}
