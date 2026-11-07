package de.alexanderlindhorst.imapmcp.transport.http;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
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
        final List<Runnable> streams = new ArrayList<>();

        Entry(String principal, Instant lastUsed) {
            this.principal = principal;
            this.lastUsed = lastUsed;
        }
    }

    /** A session that left, with the open streams to close. */
    private record Ended(String id, List<Runnable> streams) {
    }

    private final int perPrincipal;
    private final int perReplica;
    private final Duration idle;
    private final Clock clock;
    private final Consumer<String> onEnd;
    private final Object lock = new Object();
    /** Insertion order; an entry moves to the end only when its owner uses it. A foreign probe changes nothing. */
    private final Map<String, Entry> sessions = new LinkedHashMap<>();
    private int pending; // initialize requests in flight that may become a session
    private final Map<String, Integer> pendingByPrincipal = new HashMap<>();

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

    /**
     * Reserves room for one session of {@code principal} about to be created; false when the replica is full or the
     * principal already has as many initializes in flight as it may have sessions.
     */
    boolean tryReserve(String principal) {
        Objects.requireNonNull(principal, "principal");
        List<Ended> ended = new ArrayList<>();
        boolean ok;
        synchronized (lock) {
            prune(ended);
            ok = sessions.size() + pending < perReplica && pendingByPrincipal.getOrDefault(principal, 0) < perPrincipal;
            if (ok) {
                pending++;
                pendingByPrincipal.merge(principal, 1, Integer::sum);
            }
        }
        notifyEnded(ended);
        return ok;
    }

    /** Gives back a reservation that did not become a session. */
    void release(String principal) {
        synchronized (lock) {
            unreserve(principal);
        }
    }

    /** Turns a reservation of {@code principal} into the session {@code sessionId}. */
    void register(String sessionId, String principal) {
        add(sessionId, principal);
    }

    private void add(String sessionId, String principal) {
        Objects.requireNonNull(sessionId, "sessionId");
        Objects.requireNonNull(principal, "principal");
        List<Ended> ended = new ArrayList<>();
        synchronized (lock) {
            unreserve(principal);
            prune(ended);
            Entry old = sessions.remove(sessionId);
            if (old != null) {
                ended.add(new Ended(sessionId, old.streams));
            }
            while (countOf(principal) >= perPrincipal) {
                evictEldestOf(principal, ended);
            }
            sessions.put(sessionId, new Entry(principal, clock.instant()));
        }
        notifyEnded(ended);
    }

    private void unreserve(String principal) {
        if (pending > 0 && pendingByPrincipal.getOrDefault(principal, 0) > 0) {
            pending--;
            pendingByPrincipal.merge(principal, -1, Integer::sum);
            pendingByPrincipal.remove(principal, 0);
        }
    }

    /**
     * True (and the session counts as used now) iff {@code sessionId} exists, is not idle past the timeout and belongs
     * to {@code principal}. A call for a session of another principal changes nothing about that session.
     */
    boolean touch(String sessionId, String principal) {
        if (sessionId == null || principal == null) {
            return false;
        }
        List<Ended> ended = new ArrayList<>();
        boolean ok = false;
        synchronized (lock) {
            prune(ended);
            Entry e = sessions.get(sessionId);
            if (e != null && e.principal.equals(principal)) {
                Instant now = clock.instant();
                if (e.lastUsed.isAfter(now.minus(idle))) {
                    e.lastUsed = now;
                    sessions.remove(sessionId);
                    sessions.put(sessionId, e); // moves to the end: owner use only
                    ok = true;
                } else {
                    sessions.remove(sessionId);
                    ended.add(new Ended(sessionId, e.streams));
                }
            }
        }
        notifyEnded(ended);
        return ok;
    }

    /** Ties an open stream of the session to it: {@code closer} runs when the session ends (or at once if it has). */
    void attachStream(String sessionId, Runnable closer) {
        boolean known;
        synchronized (lock) {
            Entry e = sessions.get(sessionId);
            known = e != null;
            if (known) {
                e.streams.add(closer);
            }
        }
        if (!known) {
            runQuietly(closer);
        }
    }

    /** The stream finished by itself. */
    void detachStream(String sessionId, Runnable closer) {
        synchronized (lock) {
            Entry e = sessions.get(sessionId);
            if (e != null) {
                e.streams.remove(closer);
            }
        }
    }

    void remove(String sessionId) {
        Entry e;
        synchronized (lock) {
            e = sessions.remove(sessionId);
        }
        if (e != null) {
            notifyEnded(List.of(new Ended(sessionId, e.streams)));
        }
    }

    int size() {
        List<Ended> ended = new ArrayList<>();
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

    /** Evicts the principal's session that was used least recently (by timestamp; no order is assumed). */
    private void evictEldestOf(String principal, List<Ended> ended) {
        String eldest = null;
        Instant eldestUsed = null;
        for (Map.Entry<String, Entry> e : sessions.entrySet()) {
            if (e.getValue().principal.equals(principal)
                    && (eldestUsed == null || e.getValue().lastUsed.isBefore(eldestUsed))) {
                eldest = e.getKey();
                eldestUsed = e.getValue().lastUsed;
            }
        }
        if (eldest != null) {
            Entry gone = sessions.remove(eldest);
            ended.add(new Ended(eldest, gone.streams));
        }
    }

    /** Drops every session idle past the timeout (a full scan: at most the replica cap, and no order is assumed). */
    private void prune(List<Ended> ended) {
        Instant cutoff = clock.instant().minus(idle);
        for (Iterator<Map.Entry<String, Entry>> it = sessions.entrySet().iterator(); it.hasNext();) {
            Map.Entry<String, Entry> e = it.next();
            if (!e.getValue().lastUsed.isAfter(cutoff)) {
                it.remove();
                ended.add(new Ended(e.getKey(), e.getValue().streams));
            }
        }
    }

    private void notifyEnded(List<Ended> ended) {
        for (Ended e : ended) {
            for (Runnable closer : e.streams()) {
                runQuietly(closer);
            }
            try {
                onEnd.accept(e.id());
            } catch (RuntimeException ignored) {
                // the listener must never break session handling
            }
        }
    }

    private static void runQuietly(Runnable r) {
        try {
            r.run();
        } catch (RuntimeException ignored) {
            // a stream that is already complete
        }
    }
}
