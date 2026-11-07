package de.alexanderlindhorst.imapmcp.core.metrics;

import static org.assertj.core.api.Assertions.assertThat;

import de.alexanderlindhorst.imapmcp.core.account.PoolKey;
import de.alexanderlindhorst.imapmcp.core.account.MailAccount;
import de.alexanderlindhorst.imapmcp.core.account.MailAccount.Flavor;
import de.alexanderlindhorst.imapmcp.core.account.MailAccount.Security;
import de.alexanderlindhorst.imapmcp.core.account.ResolvedAccount;
import de.alexanderlindhorst.imapmcp.core.account.SecretRef;
import de.alexanderlindhorst.imapmcp.core.crypto.KeyRing;
import de.alexanderlindhorst.imapmcp.core.crypto.LogIds;
import de.alexanderlindhorst.imapmcp.core.metrics.MetricsSnapshot.OperationMetrics;
import de.alexanderlindhorst.imapmcp.core.metrics.MetricsSnapshot.PoolMetrics;
import de.alexanderlindhorst.imapmcp.core.metrics.MetricsSnapshot.TenantMetrics;
import de.alexanderlindhorst.imapmcp.core.tenancy.AccountContext;
import de.alexanderlindhorst.imapmcp.core.tenancy.AccountId;
import de.alexanderlindhorst.imapmcp.core.tenancy.TenantId;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import org.junit.jupiter.api.Test;

class InMemoryMetricsRecorderTest {

    static final TenantId T1 = new TenantId("raw-tenant-one");
    static final TenantId T2 = new TenantId("raw-tenant-two");

    final LogIds logIds = new LogIds(new KeyRing(1, Map.of(1, new byte[32])));
    final MutableClock clock = new MutableClock();
    final InMemoryMetricsRecorder rec = new InMemoryMetricsRecorder(logIds, clock);

    static final class MutableClock extends Clock {
        Instant now = Instant.parse("2026-01-01T00:00:00Z");

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

    static PoolKey key(TenantId t, String account) {
        MailAccount a = new MailAccount(new AccountId(account), "label", null, "imap.example.org", 993,
                Security.IMPLICIT_TLS, Flavor.GENERIC, "user@example.org", new SecretRef("env:X"));
        return PoolKey.of(new AccountContext(t), new ResolvedAccount(t, a, "acc_test"));
    }

    @Test
    void nowFollowsTheInjectedClock() {
        clock.now = clock.now.plusSeconds(7);
        assertThat(rec.now()).isEqualTo(clock.now);
    }

    @Test
    void latencyCountAvgMaxUpdateCorrectly() {
        rec.operation(T1, "search_messages", "search", Duration.ofMillis(100));
        rec.operation(T1, "search_messages", "search", Duration.ofMillis(300));
        rec.operation(T1, "search_messages", "search", Duration.ofMillis(200));
        rec.operation(T1, "search_messages", "fetch", Duration.ofMillis(50));
        rec.operation(T2, "search_messages", "search", Duration.ofMillis(1000));

        Map<String, OperationMetrics> byKey = new java.util.HashMap<>();
        for (OperationMetrics o : rec.snapshot().operations()) {
            byKey.put(o.tenant() + "/" + o.tool() + "/" + o.op(), o);
        }
        String t1 = logIds.tenant(T1.value());
        OperationMetrics search = byKey.get(t1 + "/search_messages/search");
        assertThat(search.count()).isEqualTo(3);
        assertThat(search.avg()).isEqualTo(Duration.ofMillis(200));
        assertThat(search.max()).isEqualTo(Duration.ofMillis(300));
        assertThat(byKey.get(t1 + "/search_messages/fetch").count()).isEqualTo(1);
        OperationMetrics other = byKey.get(logIds.tenant(T2.value()) + "/search_messages/search");
        assertThat(other.max()).isEqualTo(Duration.ofMillis(1000));
        assertThat(byKey).hasSize(3);
    }

    @Test
    void decryptFailuresAreCountedAsACredentialsOperationPerHashedTenant() {
        rec.decryptFailure(T1);
        rec.decryptFailure(T1);
        OperationMetrics o = rec.snapshot().operations().stream()
                .filter(x -> x.op().equals("decrypt_failure")).findFirst().orElseThrow();
        assertThat(o.tool()).isEqualTo("credentials");
        assertThat(o.tenant()).isEqualTo(logIds.tenant(T1.value()));
        assertThat(o.count()).isEqualTo(2);
        assertThat(o.max()).isEqualTo(Duration.ZERO);
        assertThat(MetricsRecorder.NOOP).isNotNull();
        MetricsRecorder.NOOP.decryptFailure(T1); // default method: discards
    }

    @Test
    void keyServiceOutagesAreCountedSeparatelyFromDecryptFailures() {
        rec.keyServiceUnavailable(T1);
        rec.decryptFailure(T1);
        rec.keyServiceUnavailable(T1);
        var byOp = new java.util.HashMap<String, OperationMetrics>();
        rec.snapshot().operations().forEach(o -> byOp.put(o.tool() + "/" + o.op(), o));
        assertThat(byOp.get("credentials/key_service_unavailable").count()).isEqualTo(2);
        assertThat(byOp.get("credentials/key_service_unavailable").tenant()).isEqualTo(logIds.tenant(T1.value()));
        assertThat(byOp.get("credentials/decrypt_failure").count()).isEqualTo(1);
        MetricsRecorder.NOOP.keyServiceUnavailable(T1); // default method: discards
    }

    @Test
    void initializeRateLimitingIsCountedPerHashedTenant() {
        rec.initializeRateLimited(T1);
        rec.initializeRateLimited(T1);
        OperationMetrics o = rec.snapshot().operations().stream()
                .filter(x -> x.op().equals("rate_limited")).findFirst().orElseThrow();
        assertThat(o.tool()).isEqualTo("initialize");
        assertThat(o.tenant()).isEqualTo(logIds.tenant(T1.value()));
        assertThat(o.count()).isEqualTo(2);
        MetricsRecorder.NOOP.initializeRateLimited(T1); // default method: discards
    }

    @Test
    void authFailuresAndTimeoutsCountPerHashedTenantOnly() {
        rec.authFailure(T1);
        rec.authFailure(T1);
        rec.timeout(T2);
        Map<String, TenantMetrics> m = new java.util.HashMap<>();
        rec.snapshot().tenants().forEach(t -> m.put(t.tenant(), t));
        TenantMetrics one = m.get(logIds.tenant(T1.value()));
        TenantMetrics two = m.get(logIds.tenant(T2.value()));
        assertThat(one.authFailures()).isEqualTo(2);
        assertThat(one.timeouts()).isZero();
        assertThat(two.authFailures()).isZero();
        assertThat(two.timeouts()).isEqualTo(1);
    }

    @Test
    void busyRejectionsCountPerKeyAndScope() {
        PoolKey k = key(T1, "acc-1");
        rec.busyRejection(k, "global");
        rec.busyRejection(k, "mailbox");
        rec.busyRejection(k, "mailbox");
        PoolMetrics p = rec.snapshot().pools().getFirst();
        assertThat(p.tenant()).isEqualTo(logIds.tenant(T1.value()));
        assertThat(p.account()).isEqualTo(logIds.account("acc-1"));
        assertThat(p.busyRejections()).containsEntry("global", 1L).containsEntry("mailbox", 2L);
    }

    @Test
    void leaseWaitAndPoolGauges() {
        PoolKey k = key(T1, "acc-1");
        rec.attachPool(new PoolGauges() {
            public int numActive(PoolKey key) { return 2; }
            public int numIdle(PoolKey key) { return 3; }
            public long createdCount() { return 7; }
            public long destroyedCount() { return 4; }
        });
        rec.leaseAcquired(k, Duration.ofMillis(10));
        rec.leaseAcquired(k, Duration.ofMillis(30));
        MetricsSnapshot s = rec.snapshot();
        PoolMetrics p = s.pools().getFirst();
        assertThat(p.leases()).isEqualTo(2);
        assertThat(p.avgWait()).isEqualTo(Duration.ofMillis(20));
        assertThat(p.maxWait()).isEqualTo(Duration.ofMillis(30));
        assertThat(p.active()).isEqualTo(2);
        assertThat(p.idle()).isEqualTo(3);
        assertThat(s.createdTotal()).isEqualTo(7);
        assertThat(s.destroyedTotal()).isEqualTo(4);
    }

    private static final Duration TWO_PERIODS_PLUS = InMemoryMetricsRecorder.DEFAULT_SUMMARY_PERIOD.multipliedBy(2)
            .plusSeconds(1);

    @Test
    void seriesNotUpdatedForTwoSummaryPeriodsAreDroppedAndUpdatedOnesStay() {
        rec.authFailure(T1);
        rec.operation(T1, "search_messages", "search", Duration.ofMillis(5));
        rec.leaseAcquired(key(T1, "old"), Duration.ofMillis(1));
        rec.leaseAcquired(key(T2, "live"), Duration.ofMillis(1));
        clock.now = clock.now.plus(TWO_PERIODS_PLUS.minusSeconds(60));
        rec.leaseAcquired(key(T2, "live"), Duration.ofMillis(1)); // refreshed
        clock.now = clock.now.plusSeconds(60);
        MetricsSnapshot s = rec.snapshot();
        assertThat(s.tenants()).isEmpty();
        assertThat(s.operations()).isEmpty();
        assertThat(s.pools()).hasSize(1);
        assertThat(s.pools().get(0).leases()).isEqualTo(2);
        assertThat(rec.seriesCount()).isEqualTo(1);
    }

    @Test
    void periodGivenToStartDecidesTheIdleLimit() throws Exception {
        rec.authFailure(T1);
        java.util.concurrent.ScheduledThreadPoolExecutor sched = new java.util.concurrent.ScheduledThreadPoolExecutor(1);
        try {
            rec.start(sched, Duration.ofSeconds(30));
            clock.now = clock.now.plusSeconds(61);
            assertThat(rec.snapshot().tenants()).isEmpty();
        } finally {
            rec.close();
            sched.shutdownNow();
        }
    }

    @Test
    void seriesBeyondTheCapAreCountedInOneOverflowSeries() {
        InMemoryMetricsRecorder capped = new InMemoryMetricsRecorder(logIds, clock, 2);
        capped.authFailure(new TenantId("a"));
        capped.authFailure(new TenantId("b"));
        capped.authFailure(new TenantId("c"));
        capped.authFailure(new TenantId("c"));
        capped.authFailure(new TenantId("a")); // existing series still count
        MetricsSnapshot s = capped.snapshot();
        assertThat(s.tenants()).hasSize(2);
        assertThat(s.tenants()).filteredOn(t -> t.authFailures() == 2).hasSize(1);
        assertThat(s.overflowEvents()).isEqualTo(2);
        assertThat(InMemoryMetricsRecorder.MAX_SERIES).isEqualTo(10_000);
    }

    @Test
    void expiredSeriesMakeRoomBeforeAnythingOverflows() {
        InMemoryMetricsRecorder capped = new InMemoryMetricsRecorder(logIds, clock, 1);
        capped.authFailure(new TenantId("a"));
        clock.now = clock.now.plus(TWO_PERIODS_PLUS);
        capped.authFailure(new TenantId("b"));
        MetricsSnapshot s = capped.snapshot();
        assertThat(s.tenants()).hasSize(1);
        assertThat(s.overflowEvents()).isZero();
    }
}
