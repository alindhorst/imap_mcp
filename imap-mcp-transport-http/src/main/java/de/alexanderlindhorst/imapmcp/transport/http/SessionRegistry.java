package de.alexanderlindhorst.imapmcp.transport.http;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Consumer;

/**
 * The authoritative record of the stateful transport's sessions (SPEC 4, v3b): which principal each session id was
 * issued to, and the caps. At most {@link #MAX_PER_PRINCIPAL} sessions per principal (a new one evicts that
 * principal's least recently used), {@link #MAX_PER_REPLICA} per replica, {@link #IDLE_TIMEOUT} idle. A session
 * that is not here is unknown, whatever the SDK still holds. The end listener runs for every session that leaves
 * (eviction, idle, {@code DELETE}), outside the lock; its failures are swallowed. Session ids are never logged.
 */
final class SessionRegistry {

    static final int MAX_PER_PRINCIPAL = 10;
    static final int MAX_PER_REPLICA = 1000;
    static final Duration IDLE_TIMEOUT = Duration.ofMinutes(30);

    private static final class Entry {
        final String principal;
        Instant lastUsed;

        Entry(String principal, Instant lastUsed) {
            this.principal = principal;
            this.lastUsed = lastUsed;
        }
    }

    private final int perPrincipal;
    private final int perReplica;
    private final Duration idle;
    private final Clock clock;
    private final Consumer<String> onEnd;
    private final Object lock = new Object();
    /** Access order: eldest entry is the least recently used. */
    private final Map<String, Entry> sessions = new LinkedHashMap<>(16, 0.75f, true);
    private int pending; // initialize requests in flight that may become a session

    SessionRegistry(int perPrincipal, int perReplica, Duration idle, Clock clock, Consumer<String> onEnd) {
        if (perPrincipal < 1 || perReplica < 1 || idle.isNegative() || idle.isZero()) {
            throw new IllegalArgumentException("session limits must be positive");
        }
        this.perPrincipal = perPrincipal;
        this.perReplica = perReplica;
        this.idle = idle;
        this.clock = Objects.requireNonNull(clock, "clock");
        this.onEnd = Objects.requireNonNull(onEnd, "onEnd");
    }

    /** Reserves room for one session about to be created; false when the replica is full. */
    boolean tryReserve() {
        List<String> ended = new ArrayList<>();
        boolean ok;
        synchronized (lock) {
            prune(ended);
            ok = sessions.size() + pending < perReplica;
            if (ok) {
                pending++;
            }
        }
        notifyEnded(ended);
        return ok;
    }

    /** Gives back a reservation that did not become a session. */
    void release() {
        synchronized (lock) {
            if (pending > 0) {
                pending--;
            }
        }
    }

    /** Turns a reservation into the session {@code sessionId} of {@code principal}. */
    void register(String sessionId, String principal) {
        Objects.requireNonNull(sessionId, "sessionId");
        Objects.requireNonNull(principal, "principal");
        List<String> ended = new ArrayList<>();
        synchronized (lock) {
            if (pending > 0) {
                pending--;
            }
            prune(ended);
            while (countOf(principal) >= perPrincipal) {
                evictEldestOf(principal, ended);
            }
            sessions.put(sessionId, new Entry(principal, clock.instant()));
        }
        notifyEnded(ended);
    }

    /** True (and the session counts as used now) iff {@code sessionId} exists and belongs to {@code principal}. */
    boolean touch(String sessionId, String principal) {
        if (sessionId == null || principal == null) {
            return false;
        }
        List<String> ended = new ArrayList<>();
        boolean ok;
        synchronized (lock) {
            prune(ended);
            Entry e = sessions.get(sessionId);
            ok = e != null && e.principal.equals(principal);
            if (ok) {
                e.lastUsed = clock.instant();
            }
        }
        notifyEnded(ended);
        return ok;
    }

    void remove(String sessionId) {
        boolean existed;
        synchronized (lock) {
            existed = sessions.remove(sessionId) != null;
        }
        if (existed) {
            notifyEnded(List.of(sessionId));
        }
    }

    int size() {
        List<String> ended = new ArrayList<>();
        int n;
        synchronized (lock) {
            prune(ended);
            n = sessions.size();
        }
        notifyEnded(ended);
        return n;
    }

    private int countOf(String principal) {
        int n = 0;
        for (Entry e : sessions.values()) {
            if (e.principal.equals(principal)) {
                n++;
            }
        }
        return n;
    }

    private void evictEldestOf(String principal, List<String> ended) {
        for (Iterator<Map.Entry<String, Entry>> it = sessions.entrySet().iterator(); it.hasNext();) {
            Map.Entry<String, Entry> e = it.next();
            if (e.getValue().principal.equals(principal)) {
                it.remove();
                ended.add(e.getKey());
                return;
            }
        }
    }

    /** Drops sessions idle for the timeout; access order keeps lastUsed non-decreasing from eldest to youngest. */
    private void prune(List<String> ended) {
        Instant cutoff = clock.instant().minus(idle);
        for (Iterator<Map.Entry<String, Entry>> it = sessions.entrySet().iterator(); it.hasNext();) {
            Map.Entry<String, Entry> e = it.next();
            if (e.getValue().lastUsed.isAfter(cutoff)) {
                break;
            }
            it.remove();
            ended.add(e.getKey());
        }
    }

    private void notifyEnded(List<String> ended) {
        for (String id : ended) {
            try {
                onEnd.accept(id);
            } catch (RuntimeException ignored) {
                // the listener must never break session handling
            }
        }
    }
}
