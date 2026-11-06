package de.alexanderlindhorst.imapmcp.core.linking;

import static org.assertj.core.api.Assertions.assertThat;

import de.alexanderlindhorst.imapmcp.core.tenancy.TenantId;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;

class ProbeRateLimiterTest {

    private static final class TestClock extends Clock {
        Instant now = Instant.parse("2026-11-06T10:00:00Z");

        @Override
        public java.time.ZoneId getZone() {
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

    @Test
    void allowsTheLimitWithinTheWindowThenRefusesAndRecoversAsTheWindowSlides() {
        TestClock clock = new TestClock();
        ProbeRateLimiter limiter = new ProbeRateLimiter(clock, 3, Duration.ofHours(1), 10);
        TenantId t = new TenantId("t");

        assertThat(limiter.tryAcquire(t)).isTrue();
        clock.now = clock.now.plus(Duration.ofMinutes(20));
        assertThat(limiter.tryAcquire(t)).isTrue();
        clock.now = clock.now.plus(Duration.ofMinutes(20));
        assertThat(limiter.tryAcquire(t)).isTrue();
        assertThat(limiter.tryAcquire(t)).isFalse();
        clock.now = clock.now.plus(Duration.ofMinutes(21)); // the first one is 61 minutes old
        assertThat(limiter.tryAcquire(t)).isTrue();
        assertThat(limiter.tryAcquire(t)).isFalse();
    }

    @Test
    void whenFullOfLiveEntriesANewTenantIsRefusedAndNoLiveEntryIsEvicted() {
        TestClock clock = new TestClock();
        ProbeRateLimiter limiter = new ProbeRateLimiter(clock, 1, Duration.ofHours(1), 3);
        for (int i = 0; i < 3; i++) {
            assertThat(limiter.tryAcquire(new TenantId("t" + i))).isTrue();
        }

        assertThat(limiter.tryAcquire(new TenantId("newcomer"))).isFalse(); // fail-closed, nothing evicted
        assertThat(limiter.trackedTenants()).isEqualTo(3);
        for (int i = 0; i < 3; i++) { // the tracked tenants stay limited: no way to reset a limit by flooding
            assertThat(limiter.tryAcquire(new TenantId("t" + i))).isFalse();
        }
    }

    @Test
    void expiredEntriesMakeRoomForNewTenants() {
        TestClock clock = new TestClock();
        ProbeRateLimiter limiter = new ProbeRateLimiter(clock, 1, Duration.ofHours(1), 2);
        limiter.tryAcquire(new TenantId("a"));
        limiter.tryAcquire(new TenantId("b"));
        clock.now = clock.now.plus(Duration.ofMinutes(61));

        assertThat(limiter.tryAcquire(new TenantId("c"))).isTrue();
        assertThat(limiter.trackedTenants()).isEqualTo(1);
    }

    @Test
    void entriesWhoseWindowPassedAreDropped() {
        TestClock clock = new TestClock();
        ProbeRateLimiter limiter = new ProbeRateLimiter(clock, 1, Duration.ofHours(1), 100);
        limiter.tryAcquire(new TenantId("a"));
        limiter.tryAcquire(new TenantId("b"));
        clock.now = clock.now.plus(Duration.ofHours(2));

        limiter.tryAcquire(new TenantId("c"));

        assertThat(limiter.trackedTenants()).isEqualTo(1);
    }
}
