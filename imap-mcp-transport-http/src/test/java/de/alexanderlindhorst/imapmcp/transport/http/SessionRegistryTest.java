package de.alexanderlindhorst.imapmcp.transport.http;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class SessionRegistryTest {

    private static final class FakeClock extends Clock {
        Instant now = Instant.parse("2026-11-06T12:00:00Z");

        @Override
        public ZoneOffset getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(java.time.ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }

    private final FakeClock clock = new FakeClock();
    private final List<String> ended = new ArrayList<>();

    private SessionRegistry registry(int perPrincipal, int perReplica) {
        return new SessionRegistry(perPrincipal, perReplica, Duration.ofMinutes(30), clock, ended::add);
    }

    private static void open(SessionRegistry r, String principal, String session) {
        assertThat(r.tryReserve()).isTrue();
        r.register(session, principal);
    }

    @Test
    void defaultsAre10PerPrincipal1000PerReplicaAnd30Minutes() {
        assertThat(SessionRegistry.MAX_PER_PRINCIPAL).isEqualTo(10);
        assertThat(SessionRegistry.MAX_PER_REPLICA).isEqualTo(1000);
        assertThat(SessionRegistry.IDLE_TIMEOUT).isEqualTo(Duration.ofMinutes(30));
    }

    @Test
    void aSessionIsOnlyValidForThePrincipalItWasBoundTo() {
        SessionRegistry r = registry(10, 100);
        open(r, "alice", "s1");
        assertThat(r.touch("s1", "alice")).isTrue();
        assertThat(r.touch("s1", "bob")).isFalse();
        assertThat(r.touch("unknown", "alice")).isFalse();
        assertThat(r.touch(null, "alice")).isFalse();
        assertThat(ended).isEmpty(); // a foreign probe does not end the owner's session
        assertThat(r.touch("s1", "alice")).isTrue();
    }

    @Test
    void theLeastRecentlyUsedSessionOfAPrincipalIsEvictedAtTheCap() {
        SessionRegistry r = registry(2, 100);
        open(r, "alice", "s1");
        open(r, "alice", "s2");
        open(r, "bob", "b1");
        assertThat(r.touch("s1", "alice")).isTrue(); // s2 is now the least recently used
        open(r, "alice", "s3");
        assertThat(ended).containsExactly("s2");
        assertThat(r.touch("s2", "alice")).isFalse();
        assertThat(r.touch("s1", "alice")).isTrue();
        assertThat(r.touch("s3", "alice")).isTrue();
        assertThat(r.touch("b1", "bob")).isTrue(); // another principal is untouched
        assertThat(r.size()).isEqualTo(3);
    }

    @Test
    void theReplicaCapRefusesFurtherSessionsAndCountsPendingReservations() {
        SessionRegistry r = registry(10, 2);
        assertThat(r.tryReserve()).isTrue();
        assertThat(r.tryReserve()).isTrue();
        assertThat(r.tryReserve()).isFalse(); // two initializes in flight fill the replica
        r.register("s1", "alice");
        r.release(); // the second initialize produced no session
        assertThat(r.tryReserve()).isTrue();
        r.register("s2", "bob");
        assertThat(r.tryReserve()).isFalse();
        assertThat(r.size()).isEqualTo(2);
    }

    @Test
    void sessionsIdleForThirtyMinutesAreEvictedAndUseRefreshesThem() {
        SessionRegistry r = registry(10, 100);
        open(r, "alice", "kept");
        open(r, "alice", "idle");
        clock.now = clock.now.plus(Duration.ofMinutes(20));
        assertThat(r.touch("kept", "alice")).isTrue();
        clock.now = clock.now.plus(Duration.ofMinutes(11));
        assertThat(r.touch("idle", "alice")).isFalse();
        assertThat(ended).containsExactly("idle");
        assertThat(r.touch("kept", "alice")).isTrue();
        clock.now = clock.now.plus(Duration.ofMinutes(31));
        assertThat(r.size()).isZero();
        assertThat(ended).containsExactly("idle", "kept");
    }

    @Test
    void removeEndsTheSessionOnceAndAnEvictedSessionStaysGone() {
        SessionRegistry r = registry(10, 100);
        open(r, "alice", "s1");
        r.remove("s1");
        r.remove("s1");
        r.remove("never");
        assertThat(ended).containsExactly("s1");
        assertThat(r.touch("s1", "alice")).isFalse();
    }

    @Test
    void aFailingEndListenerNeverBreaksTheRegistry() {
        SessionRegistry r = new SessionRegistry(10, 100, Duration.ofMinutes(30), clock, id -> {
            throw new IllegalStateException("boom");
        });
        open(r, "alice", "s1");
        r.remove("s1");
        assertThat(r.size()).isZero();
    }
}
