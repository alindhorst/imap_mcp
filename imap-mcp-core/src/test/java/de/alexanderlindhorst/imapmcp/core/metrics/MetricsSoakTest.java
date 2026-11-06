package de.alexanderlindhorst.imapmcp.core.metrics;

import static org.assertj.core.api.Assertions.assertThat;

import de.alexanderlindhorst.imapmcp.core.crypto.KeyRing;
import de.alexanderlindhorst.imapmcp.core.crypto.LogIds;
import de.alexanderlindhorst.imapmcp.core.tenancy.TenantId;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Soak of the metrics series with many synthetic tenants (SPEC 5.5, 5.6, 5.8): bounded, idle series expire. */
class MetricsSoakTest {

    private static final int TENANTS = 20_000;

    private static final class MutableClock extends Clock {
        Instant now = Instant.parse("2026-11-06T12:00:00Z");

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }

    @Test
    void seriesStayAtTheCapPerKindAndIdleSeriesExpire() {
        MutableClock clock = new MutableClock();
        InMemoryMetricsRecorder rec = new InMemoryMetricsRecorder(new LogIds(new KeyRing(1, Map.of(1, new byte[32]))),
                clock);
        for (int i = 0; i < TENANTS; i++) {
            TenantId t = new TenantId("tenant-" + i);
            rec.operation(t, "search_messages", "search", Duration.ofMillis(5));
            rec.authFailure(t);
            assertThat(rec.seriesCount()).isLessThanOrEqualTo(2 * InMemoryMetricsRecorder.MAX_SERIES);
        }
        assertThat(rec.seriesCount()).isEqualTo(2 * InMemoryMetricsRecorder.MAX_SERIES);
        assertThat(rec.snapshot().operations()).hasSizeLessThanOrEqualTo(InMemoryMetricsRecorder.MAX_SERIES);
        clock.now = clock.now.plus(Duration.ofDays(1));
        assertThat(rec.snapshot().operations()).isEmpty(); // a snapshot drops what was idle
        assertThat(rec.seriesCount()).isZero();
    }
}
