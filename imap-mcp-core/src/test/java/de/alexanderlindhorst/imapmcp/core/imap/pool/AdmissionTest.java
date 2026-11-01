package de.alexanderlindhorst.imapmcp.core.imap.pool;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import de.alexanderlindhorst.imapmcp.core.account.MailAccount;
import de.alexanderlindhorst.imapmcp.core.account.MailAccount.Flavor;
import de.alexanderlindhorst.imapmcp.core.account.MailAccount.Security;
import de.alexanderlindhorst.imapmcp.core.account.PoolKey;
import de.alexanderlindhorst.imapmcp.core.account.ResolvedAccount;
import de.alexanderlindhorst.imapmcp.core.account.SecretRef;
import de.alexanderlindhorst.imapmcp.core.error.ServerBusyException;
import de.alexanderlindhorst.imapmcp.core.metrics.MetricsRecorder;
import de.alexanderlindhorst.imapmcp.core.tenancy.AccountContext;
import de.alexanderlindhorst.imapmcp.core.tenancy.AccountId;
import de.alexanderlindhorst.imapmcp.core.tenancy.TenantId;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ThreadLocalRandom;
import org.junit.jupiter.api.Test;

class AdmissionTest {

    private static PoolKey key(String tenant, String account) {
        TenantId t = new TenantId(tenant);
        MailAccount a = new MailAccount(new AccountId(account), "l", null, "h", 993, Security.IMPLICIT_TLS,
                Flavor.GENERIC, "u", new SecretRef("env:X"));
        return PoolKey.of(new AccountContext(t), new ResolvedAccount(t, a));
    }

    private static PoolSettings settings(int mailbox, int tenant, int total) {
        return new PoolSettings(mailbox, tenant, total, Duration.ofMinutes(5), Duration.ofSeconds(60),
                Duration.ofMinutes(30), Duration.ofSeconds(10), Duration.ofSeconds(30), Duration.ofSeconds(120));
    }

    private static void assertAllFree(Admission a, PoolSettings s, PoolKey... keys) {
        assertThat(a.availableGlobal()).isEqualTo(s.maxTotal());
        for (PoolKey k : keys) {
            assertThat(a.availableTenant(k.tenantId())).isEqualTo(s.maxPerTenant());
            assertThat(a.availableMailbox(k)).isEqualTo(s.maxPerMailbox());
        }
    }

    @Test
    void defaultsAre64Global8Tenant3Mailbox() {
        PoolSettings d = PoolSettings.DEFAULTS;
        assertThat(d.maxTotal()).isEqualTo(64);
        assertThat(d.maxPerTenant()).isEqualTo(8);
        assertThat(d.maxPerMailbox()).isEqualTo(3);
        Admission a = new Admission(d);
        PoolKey k = key("t", "a");
        assertThat(a.availableGlobal()).isEqualTo(64);
        assertThat(a.availableTenant(k.tenantId())).isEqualTo(8);
        assertThat(a.availableMailbox(k)).isEqualTo(3);
    }

    @Test
    void mailboxLimitFailsFastAndReleaseRestores() {
        PoolSettings s = settings(1, 8, 64);
        Admission a = new Admission(s);
        PoolKey k = key("t", "a");
        Admission.Permit p = a.tryAcquire(k);
        long start = System.nanoTime();
        assertThatThrownBy(() -> a.tryAcquire(k)).isInstanceOf(ServerBusyException.class);
        assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(Duration.ofMillis(100));
        // the failed attempt must not keep the outer permits
        assertThat(a.availableGlobal()).isEqualTo(63);
        assertThat(a.availableTenant(k.tenantId())).isEqualTo(7);
        p.close();
        assertAllFree(a, s, k);
    }

    @Test
    void tenantSaturationDoesNotBlockOtherTenant() {
        PoolSettings s = settings(3, 1, 64);
        Admission a = new Admission(s);
        PoolKey a1 = key("A", "x");
        PoolKey b1 = key("B", "x");
        Admission.Permit held = a.tryAcquire(a1);
        assertThatThrownBy(() -> a.tryAcquire(key("A", "y"))).isInstanceOf(ServerBusyException.class);
        Admission.Permit other = a.tryAcquire(b1);
        other.close();
        held.close();
        assertAllFree(a, s, a1, b1, key("A", "y"));
    }

    @Test
    void globalCapRejectsAnyTenantAndReportsScope() {
        PoolSettings s = settings(3, 8, 2);
        List<Admission.Scope> rejected = new CopyOnWriteArrayList<>();
        Admission a = new Admission(s, (k, scope) -> rejected.add(scope));
        Admission.Permit p1 = a.tryAcquire(key("A", "x"));
        Admission.Permit p2 = a.tryAcquire(key("B", "x"));
        assertThatThrownBy(() -> a.tryAcquire(key("C", "x"))).isInstanceOf(ServerBusyException.class);
        assertThat(rejected).containsExactly(Admission.Scope.GLOBAL);
        p1.close();
        p2.close();
        assertThatThrownBy(() -> {
            Admission b = new Admission(settings(1, 8, 64), (k, scope) -> rejected.add(scope));
            b.tryAcquire(key("A", "x"));
            b.tryAcquire(key("A", "x"));
        }).isInstanceOf(ServerBusyException.class);
        assertThat(rejected).containsExactly(Admission.Scope.GLOBAL, Admission.Scope.MAILBOX);
    }

    @Test
    void tenantRejectionReportsTenantScope() {
        List<Admission.Scope> rejected = new ArrayList<>();
        Admission a = new Admission(settings(3, 1, 64), (k, scope) -> rejected.add(scope));
        a.tryAcquire(key("A", "x"));
        assertThatThrownBy(() -> a.tryAcquire(key("A", "y"))).isInstanceOf(ServerBusyException.class);
        assertThat(rejected).containsExactly(Admission.Scope.TENANT);
    }

    @Test
    void permitReleasesExactlyOnce() {
        PoolSettings s = settings(2, 8, 64);
        Admission a = new Admission(s);
        PoolKey k = key("t", "a");
        Admission.Permit p = a.tryAcquire(k);
        p.close();
        p.close();
        assertAllFree(a, s, k);
    }

    @Test
    void thousandMixedCallsLeakNoPermits() throws Exception {
        PoolSettings s = settings(3, 8, 20);
        Admission a = new Admission(s);
        PoolKey[] keys = {key("A", "1"), key("A", "2"), key("B", "1"), key("C", "1")};
        List<Thread> threads = new ArrayList<>();
        for (int i = 0; i < 1000; i++) {
            PoolKey k = keys[i % keys.length];
            int mode = i % 4;
            threads.add(Thread.ofVirtual().start(() -> {
                try (Admission.Permit p = a.tryAcquire(k)) {
                    Thread.sleep(ThreadLocalRandom.current().nextInt(3));
                    if (mode == 1) {
                        throw new IllegalStateException("boom");
                    }
                    if (mode == 2) {
                        Thread.currentThread().interrupt();
                    }
                } catch (ServerBusyException | IllegalStateException | InterruptedException ignored) {
                    // expected paths
                }
            }));
        }
        for (Thread t : threads) {
            t.join();
        }
        assertAllFree(a, s, keys);
    }

    @Test
    void usesSemaphoresOnlyNoThreadPool() {
        for (var f : Admission.class.getDeclaredFields()) {
            assertThat(java.util.concurrent.Executor.class.isAssignableFrom(f.getType())).isFalse();
        }
    }

    @Test
    void recordingListenerReportsKeyAndScopeToMetrics() {
        List<String> seen = new ArrayList<>();
        MetricsRecorder recorder = new MetricsRecorder() {
            @Override
            public void busyRejection(PoolKey key, String scope) {
                seen.add(key.tenantId().value() + "/" + key.accountId().value() + "/" + scope);
            }
        };
        Admission a = new Admission(settings(3, 1, 64), Admission.recording(recorder));
        a.tryAcquire(key("A", "x"));
        assertThatThrownBy(() -> a.tryAcquire(key("A", "y"))).isInstanceOf(ServerBusyException.class);
        assertThat(seen).containsExactly("A/y/tenant");
    }
}
