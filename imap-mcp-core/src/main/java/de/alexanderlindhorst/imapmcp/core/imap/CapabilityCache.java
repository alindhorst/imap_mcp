package de.alexanderlindhorst.imapmcp.core.imap;

import de.alexanderlindhorst.imapmcp.core.account.PoolKey;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Capabilities per {@code (tenant, account)} with a TTL (SPEC 5.4). Keys are internal {@link PoolKey}s only.
 * Populated by the pool factory on connect; {@link #get} distinguishes fresh, expired and missing so that
 * callers know whether to probe again.
 */
public final class CapabilityCache {

    /** Default TTL (user decision: 10 minutes); configured as {@code pool.capabilityTtl}. */
    public static final Duration DEFAULT_TTL = Duration.ofMinutes(10);

    /** Result of a lookup. */
    public sealed interface Lookup {
        record Fresh(ImapCapabilities capabilities, Instant detectedAt) implements Lookup { }

        record Expired(ImapCapabilities capabilities, Instant detectedAt) implements Lookup { }

        record Missing() implements Lookup { }
    }

    private record Stamped(ImapCapabilities capabilities, Instant detectedAt) { }

    private final Map<PoolKey, Stamped> entries = new ConcurrentHashMap<>();
    private final Duration ttl;
    private final Clock clock;

    public CapabilityCache() {
        this(DEFAULT_TTL, Clock.systemUTC());
    }

    public CapabilityCache(Clock clock) {
        this(DEFAULT_TTL, clock);
    }

    public CapabilityCache(Duration ttl, Clock clock) {
        this.ttl = Objects.requireNonNull(ttl, "ttl");
        this.clock = Objects.requireNonNull(clock, "clock");
        if (ttl.isNegative() || ttl.isZero()) {
            throw new IllegalArgumentException("ttl must be positive");
        }
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
}
