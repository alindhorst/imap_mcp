package de.alexanderlindhorst.imapmcp.core.util;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;

/**
 * Thread-safe map bounded in size (least recently used out) and in idle time (an entry not used for
 * {@code idleExpiry} is gone), for state keyed by tenant or account (SPEC 5.5). Every call may drop expired
 * entries; there is no background thread. Values are computed under the map's lock, so {@code mappingFunction}
 * must be quick and must not call back into the map.
 */
public final class ExpiringLruMap<K, V> {

    private static final class Stamped<V> {
        final V value;
        Instant lastUsed;

        Stamped(V value, Instant lastUsed) {
            this.value = value;
            this.lastUsed = lastUsed;
        }
    }

    private final Duration idleExpiry;
    private final Clock clock;
    private final LinkedHashMap<K, Stamped<V>> entries;

    public ExpiringLruMap(int maxEntries, Duration idleExpiry, Clock clock) {
        if (maxEntries < 1) {
            throw new IllegalArgumentException("maxEntries must be positive");
        }
        this.idleExpiry = Objects.requireNonNull(idleExpiry, "idleExpiry");
        if (idleExpiry.isNegative() || idleExpiry.isZero()) {
            throw new IllegalArgumentException("idleExpiry must be positive");
        }
        this.clock = Objects.requireNonNull(clock, "clock");
        this.entries = new LinkedHashMap<>(16, 0.75f, true) { // access order: eldest is least recently used
            @Override
            protected boolean removeEldestEntry(Map.Entry<K, Stamped<V>> eldest) {
                return size() > maxEntries;
            }
        };
    }

    /** The value, marked as used now, or null if absent or expired. */
    public synchronized V get(K key) {
        Instant now = prune();
        Stamped<V> s = entries.get(key);
        if (s == null) {
            return null;
        }
        s.lastUsed = now;
        return s.value;
    }

    public synchronized V put(K key, V value) {
        Instant now = prune();
        Stamped<V> old = entries.put(Objects.requireNonNull(key, "key"),
                new Stamped<>(Objects.requireNonNull(value, "value"), now));
        return old == null ? null : old.value;
    }

    public synchronized V computeIfAbsent(K key, Function<? super K, ? extends V> mappingFunction) {
        V existing = get(key);
        if (existing != null) {
            return existing;
        }
        V created = Objects.requireNonNull(mappingFunction.apply(key), "computed value");
        put(key, created);
        return created;
    }

    public synchronized V remove(K key) {
        Stamped<V> old = entries.remove(key);
        return old == null ? null : old.value;
    }

    public synchronized void clear() {
        entries.clear();
    }

    /** Number of live entries; expired ones are dropped first. */
    public synchronized int size() {
        prune();
        return entries.size();
    }

    /** Drops expired entries from the least recently used end; returns the current time. */
    private Instant prune() {
        Instant now = clock.instant();
        Instant cutoff = now.minus(idleExpiry);
        for (Iterator<Stamped<V>> it = entries.values().iterator(); it.hasNext();) {
            if (it.next().lastUsed.isAfter(cutoff)) {
                break; // access order keeps lastUsed non-decreasing from eldest to youngest
            }
            it.remove();
        }
        return now;
    }
}
