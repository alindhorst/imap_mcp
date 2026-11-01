package de.alexanderlindhorst.imapmcp.core.imap.pool;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.icegreen.greenmail.util.GreenMail;
import com.icegreen.greenmail.util.ServerSetupTest;
import de.alexanderlindhorst.imapmcp.core.account.MailAccount;
import de.alexanderlindhorst.imapmcp.core.account.MailAccount.Flavor;
import de.alexanderlindhorst.imapmcp.core.account.MailAccount.Security;
import de.alexanderlindhorst.imapmcp.core.account.PoolKey;
import de.alexanderlindhorst.imapmcp.core.account.ResolvedAccount;
import de.alexanderlindhorst.imapmcp.core.account.Secret;
import de.alexanderlindhorst.imapmcp.core.account.SecretRef;
import de.alexanderlindhorst.imapmcp.core.crypto.KeyRing;
import de.alexanderlindhorst.imapmcp.core.crypto.LogIds;
import de.alexanderlindhorst.imapmcp.core.error.ServerBusyException;
import de.alexanderlindhorst.imapmcp.core.imap.CapabilityCache;
import de.alexanderlindhorst.imapmcp.core.imap.ImapSessionFactory;
import de.alexanderlindhorst.imapmcp.core.imap.ImapTimeouts;
import de.alexanderlindhorst.imapmcp.core.metrics.InMemoryMetricsRecorder;
import de.alexanderlindhorst.imapmcp.core.metrics.MetricsSnapshot;
import de.alexanderlindhorst.imapmcp.core.tenancy.AccountContext;
import de.alexanderlindhorst.imapmcp.core.tenancy.AccountId;
import de.alexanderlindhorst.imapmcp.core.tenancy.TenantId;
import de.alexanderlindhorst.imapmcp.core.tool.ToolCallContext;
import java.time.Clock;
import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** AC1 (#20): metrics of a real lease cycle against GreenMail, and the admission hook end to end. */
class LeaseMetricsTest {

    private static final TenantId TENANT = new TenantId("t1");
    private final LogIds logIds = new LogIds(new KeyRing(1, Map.of(1, new byte[32])));
    private final InMemoryMetricsRecorder recorder = new InMemoryMetricsRecorder(logIds, Clock.systemUTC());
    private GreenMail greenMail;
    private ImapSessionFactory sessions;
    private StorePool pool;
    private PoolKey key;

    @BeforeEach
    void setUp() {
        greenMail = new GreenMail(ServerSetupTest.IMAPS.dynamicPort());
        greenMail.start();
        greenMail.setUser("u@localhost", "u", "pw");
        MailAccount account = new MailAccount(new AccountId("a"), "label", null, "127.0.0.1",
                greenMail.getImaps().getPort(), Security.IMPLICIT_TLS, Flavor.GENERIC, "u", new SecretRef("env:X"));
        key = PoolKey.of(new AccountContext(TENANT), new ResolvedAccount(TENANT, account, "acc_test"));
        sessions = new ImapSessionFactory(ImapTimeouts.DEFAULTS, p -> {
            p.put("mail.imaps.ssl.trust", "*");
            p.put("mail.imaps.ssl.checkserveridentity", "false");
            return p;
        }, true);
    }

    @AfterEach
    void tearDown() {
        recorder.close();
        pool.close();
        sessions.close();
        greenMail.stop();
    }

    private StoreLeases leases(PoolSettings s) {
        pool = new StorePool(sessions, (t, a) -> Optional.of(new Secret("pw".toCharArray())), new CapabilityCache(),
                k -> k.equals(key) ? account(k) : null, s);
        recorder.attachPool(pool);
        return new StoreLeases(pool, Duration.ofSeconds(10), new Admission(s, Admission.recording(recorder)),
                recorder);
    }

    private MailAccount account(PoolKey k) {
        return new MailAccount(new AccountId("a"), "label", null, "127.0.0.1", greenMail.getImaps().getPort(),
                Security.IMPLICIT_TLS, Flavor.GENERIC, "u", new SecretRef("env:X"));
    }

    private static PoolSettings settings(int perMailbox) {
        return new PoolSettings(perMailbox, 8, 64, Duration.ofMinutes(5), Duration.ofSeconds(60),
                Duration.ofMinutes(30), Duration.ofSeconds(10), Duration.ofSeconds(30), Duration.ofSeconds(120));
    }

    @Test
    void oneLeaseCycleShowsCreatedOneActiveZeroIdleOne() {
        StoreLeases leases = leases(settings(1));
        try (ImapLease lease = leases.lease(key, new ToolCallContext(new AccountContext(TENANT), "r"))) {
            assertThat(recorder.snapshot().pools().getFirst().active()).isEqualTo(1);
        }
        MetricsSnapshot s = recorder.snapshot();
        MetricsSnapshot.PoolMetrics p = s.pools().getFirst();
        assertThat(p.tenant()).isEqualTo(logIds.tenant("t1"));
        assertThat(p.account()).isEqualTo(logIds.account("a"));
        assertThat(s.createdTotal()).isEqualTo(1);
        assertThat(p.active()).isZero();
        assertThat(p.idle()).isEqualTo(1);
        assertThat(p.leases()).isEqualTo(1);
        assertThat(p.maxWait()).isPositive();
    }

    @Test
    void admissionRefusalIsCountedAsBusyForTheKey() {
        StoreLeases leases = leases(settings(1));
        ToolCallContext ctx = new ToolCallContext(new AccountContext(TENANT), "r");
        try (ImapLease held = leases.lease(key, ctx)) {
            assertThatThrownBy(() -> leases.lease(key, ctx)).isInstanceOf(ServerBusyException.class);
        }
        MetricsSnapshot.PoolMetrics p = recorder.snapshot().pools().getFirst();
        assertThat(p.busyRejections()).containsEntry("mailbox", 1L);
    }
}
