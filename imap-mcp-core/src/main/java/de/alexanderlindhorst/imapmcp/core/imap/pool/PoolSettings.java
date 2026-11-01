package de.alexanderlindhorst.imapmcp.core.imap.pool;

import java.time.Duration;
import java.util.Objects;

/** Pool sizes and durations with the SPEC 5.5 defaults. Config mapping ({@code pool.*}) is wired elsewhere. */
public record PoolSettings(int maxPerMailbox, int maxTotal, Duration idleEviction, Duration validateAfterIdle,
        Duration maxLifetime, Duration acquireTimeout, Duration evictionInterval, Duration abandonedTimeout) {

    public static final PoolSettings DEFAULTS = new PoolSettings(3, 64, Duration.ofMinutes(5),
            Duration.ofSeconds(60), Duration.ofMinutes(30), Duration.ofSeconds(10), Duration.ofSeconds(30),
            Duration.ofSeconds(120));

    public PoolSettings {
        if (maxPerMailbox < 1 || maxTotal < 1) {
            throw new IllegalArgumentException("pool sizes must be positive");
        }
        Objects.requireNonNull(idleEviction, "idleEviction");
        Objects.requireNonNull(validateAfterIdle, "validateAfterIdle");
        Objects.requireNonNull(maxLifetime, "maxLifetime");
        Objects.requireNonNull(acquireTimeout, "acquireTimeout");
        Objects.requireNonNull(evictionInterval, "evictionInterval");
        Objects.requireNonNull(abandonedTimeout, "abandonedTimeout");
    }

    public PoolSettings withValidateAfterIdle(Duration d) {
        return new PoolSettings(maxPerMailbox, maxTotal, idleEviction, d, maxLifetime, acquireTimeout,
                evictionInterval, abandonedTimeout);
    }

    public PoolSettings withMaxLifetime(Duration d) {
        return new PoolSettings(maxPerMailbox, maxTotal, idleEviction, validateAfterIdle, d, acquireTimeout,
                evictionInterval, abandonedTimeout);
    }

    public PoolSettings withIdleEviction(Duration d) {
        return new PoolSettings(maxPerMailbox, maxTotal, d, validateAfterIdle, maxLifetime, acquireTimeout,
                evictionInterval, abandonedTimeout);
    }

    public PoolSettings withEvictionInterval(Duration d) {
        return new PoolSettings(maxPerMailbox, maxTotal, idleEviction, validateAfterIdle, maxLifetime,
                acquireTimeout, d, abandonedTimeout);
    }
}
