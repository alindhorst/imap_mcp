package de.alexanderlindhorst.imapmcp.core.util;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class ExpiringLruMapTest {

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

    @Test
    void leastRecentlyUsedEntryLeavesWhenTheCapIsExceeded() {
        ExpiringLruMap<String, Integer> map = new ExpiringLruMap<>(2, Duration.ofHours(1), clock);
        map.put("a", 1);
        map.put("b", 2);
        assertThat(map.get("a")).isEqualTo(1); // a is now more recent than b
        map.put("c", 3);
        assertThat(map.size()).isEqualTo(2);
        assertThat(map.get("b")).isNull();
        assertThat(map.get("a")).isEqualTo(1);
        assertThat(map.get("c")).isEqualTo(3);
    }

    @Test
    void entriesUnusedForTheIdleTimeAreGoneAndUseRefreshesThem() {
        ExpiringLruMap<String, Integer> map = new ExpiringLruMap<>(10, Duration.ofHours(24), clock);
        map.put("kept", 1);
        map.put("gone", 2);
        clock.now = clock.now.plus(Duration.ofHours(23));
        assertThat(map.get("kept")).isEqualTo(1);
        clock.now = clock.now.plus(Duration.ofHours(2));
        assertThat(map.get("gone")).isNull();
        assertThat(map.size()).isEqualTo(1); // size drops the expired entry without a lookup of it
        assertThat(map.get("kept")).isEqualTo(1);
    }

    @Test
    void sizeNeverCountsExpiredEntries() {
        ExpiringLruMap<String, Integer> map = new ExpiringLruMap<>(10, Duration.ofMinutes(1), clock);
        map.put("a", 1);
        map.put("b", 2);
        clock.now = clock.now.plusSeconds(61);
        assertThat(map.size()).isZero();
    }

    @Test
    void computeIfAbsentComputesOncePerLiveKeyAndAgainAfterExpiry() {
        ExpiringLruMap<String, Integer> map = new ExpiringLruMap<>(10, Duration.ofMinutes(1), clock);
        AtomicInteger calls = new AtomicInteger();
        assertThat(map.computeIfAbsent("a", k -> calls.incrementAndGet())).isEqualTo(1);
        assertThat(map.computeIfAbsent("a", k -> calls.incrementAndGet())).isEqualTo(1);
        clock.now = clock.now.plusSeconds(61);
        assertThat(map.computeIfAbsent("a", k -> calls.incrementAndGet())).isEqualTo(2);
    }

    @Test
    void removeAndClearEmptyTheMap() {
        ExpiringLruMap<String, Integer> map = new ExpiringLruMap<>(10, Duration.ofMinutes(1), clock);
        map.put("a", 1);
        map.put("b", 2);
        map.remove("a");
        assertThat(map.size()).isEqualTo(1);
        map.clear();
        assertThat(map.size()).isZero();
    }

    @Test
    void invalidLimitsAreRejected() {
        assertThatThrownBy(() -> new ExpiringLruMap<String, String>(0, Duration.ofSeconds(1), clock))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ExpiringLruMap<String, String>(1, Duration.ZERO, clock))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
