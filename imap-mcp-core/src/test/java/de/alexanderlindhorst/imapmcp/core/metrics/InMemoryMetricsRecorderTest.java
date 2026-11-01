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
}
