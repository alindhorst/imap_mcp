package de.alexanderlindhorst.imapmcp.transport.http;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;

class InitializeRateLimiterTest {

    private static final class TestClock extends Clock {
        Instant now = Instant.parse("2026-11-07T10:00:00Z");

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
    void admitsTheCapacityOfTwiceTheRateThenRefusesWithWholeSecondsRetryAfter() {
        TestClock clock = new TestClock();
        InitializeRateLimiter limiter = new InitializeRateLimiter(clock, 10, 100);

        for (int i = 0; i < 20; i++) {
            assertThat(limiter.tryAcquire("p")).isZero();
        }
        // 10 per minute: one token every 6 s
        assertThat(limiter.tryAcquire("p")).isEqualTo(6);
        clock.now = clock.now.plusSeconds(2);
        assertThat(limiter.tryAcquire("p")).isEqualTo(4);
    }

    @Test
    void aRefusalTakesNoToken() {
        TestClock clock = new TestClock();
        InitializeRateLimiter limiter = new InitializeRateLimiter(clock, 10, 100);
        for (int i = 0; i < 20; i++) {
            limiter.tryAcquire("p");
        }
        for (int i = 0; i < 50; i++) {
            assertThat(limiter.tryAcquire("p")).isPositive();
        }
        clock.now = clock.now.plusSeconds(6);
        assertThat(limiter.tryAcquire("p")).isZero(); // exactly one token has refilled
        assertThat(limiter.tryAcquire("p")).isPositive();
    }

    @Test
    void refillsAtTheRatePerMinuteAndNeverAboveTheCapacity() {
        TestClock clock = new TestClock();
        InitializeRateLimiter limiter = new InitializeRateLimiter(clock, 60, 100); // one per second, capacity 120
        for (int i = 0; i < 120; i++) {
            assertThat(limiter.tryAcquire("p")).isZero();
        }
        assertThat(limiter.tryAcquire("p")).isEqualTo(1);
        clock.now = clock.now.plus(Duration.ofHours(1));
        for (int i = 0; i < 120; i++) {
            assertThat(limiter.tryAcquire("p")).isZero();
        }
        assertThat(limiter.tryAcquire("p")).isPositive();
    }

    @Test
    void principalsAreIndependent() {
        TestClock clock = new TestClock();
        InitializeRateLimiter limiter = new InitializeRateLimiter(clock, 1, 100); // capacity 2
        assertThat(limiter.tryAcquire("a")).isZero();
        assertThat(limiter.tryAcquire("a")).isZero();
        assertThat(limiter.tryAcquire("a")).isPositive();
        assertThat(limiter.tryAcquire("b")).isZero();
    }

    @Test
    void anEntryIsDroppedOnceItsBucketIsFullAgain() {
        TestClock clock = new TestClock();
        InitializeRateLimiter limiter = new InitializeRateLimiter(clock, 10, 100);
        limiter.tryAcquire("p");
        assertThat(limiter.trackedPrincipals()).isEqualTo(1);
        clock.now = clock.now.plusSeconds(6); // the one token is back
        limiter.tryAcquire("other"); // any call sweeps once a sweep interval has passed
        assertThat(limiter.trackedPrincipals()).isEqualTo(1);
    }

    @Test
    void whenFullRefilledBucketsAreDroppedFirstAndAnewcomerIsAdmitted() {
        TestClock clock = new TestClock();
        InitializeRateLimiter limiter = new InitializeRateLimiter(clock, 10, 3);
        for (int i = 0; i < 3; i++) {
            limiter.tryAcquire("p" + i);
        }
        assertThat(limiter.trackedPrincipals()).isEqualTo(3);
        clock.now = clock.now.plusSeconds(6); // all three are full again

        assertThat(limiter.tryAcquire("newcomer")).isZero();
        assertThat(limiter.trackedPrincipals()).isEqualTo(1);
    }

    @Test
    void whenStillFullOfLiveEntriesANewPrincipalIsRefusedAndNothingGrows() {
        TestClock clock = new TestClock();
        InitializeRateLimiter limiter = new InitializeRateLimiter(clock, 10, 3);
        for (int i = 0; i < 3; i++) {
            assertThat(limiter.tryAcquire("p" + i)).isZero();
        }

        for (int i = 0; i < 1000; i++) {
            assertThat(limiter.tryAcquire("flood" + i)).isPositive(); // fail-closed
        }
        assertThat(limiter.trackedPrincipals()).isEqualTo(3);
        assertThat(limiter.tryAcquire("p0")).isZero(); // known principals keep their own bucket
    }

    @Test
    void theDefaultBoundIs10000() {
        TestClock clock = new TestClock();
        InitializeRateLimiter limiter = new InitializeRateLimiter(clock, 10);
        for (int i = 0; i < 10_000; i++) {
            assertThat(limiter.tryAcquire("p" + i)).isZero();
        }
        assertThat(limiter.tryAcquire("one-more")).isPositive();
        assertThat(limiter.trackedPrincipals()).isEqualTo(10_000);
    }

    @Test
    void rejectsRatesOutsideOneTo600() {
        Clock clock = new TestClock();
        assertThatThrownBy(() -> new InitializeRateLimiter(clock, 0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new InitializeRateLimiter(clock, 601)).isInstanceOf(IllegalArgumentException.class);
    }
}
