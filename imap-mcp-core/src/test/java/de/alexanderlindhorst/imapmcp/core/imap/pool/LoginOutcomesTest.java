package de.alexanderlindhorst.imapmcp.core.imap.pool;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.icegreen.greenmail.util.GreenMail;
import com.icegreen.greenmail.util.ServerSetupTest;
import de.alexanderlindhorst.imapmcp.core.account.LoginOutcomes;
import de.alexanderlindhorst.imapmcp.core.account.MailAccount;
import de.alexanderlindhorst.imapmcp.core.account.MailAccount.Flavor;
import de.alexanderlindhorst.imapmcp.core.account.MailAccount.Security;
import de.alexanderlindhorst.imapmcp.core.account.PoolKey;
import de.alexanderlindhorst.imapmcp.core.account.ResolvedAccount;
import de.alexanderlindhorst.imapmcp.core.account.Secret;
import de.alexanderlindhorst.imapmcp.core.account.SecretRef;
import de.alexanderlindhorst.imapmcp.core.imap.CapabilityCache;
import de.alexanderlindhorst.imapmcp.core.imap.ImapSessionFactory;
import de.alexanderlindhorst.imapmcp.core.imap.ImapTimeouts;
import de.alexanderlindhorst.imapmcp.core.tenancy.AccountContext;
import de.alexanderlindhorst.imapmcp.core.tenancy.AccountId;
import de.alexanderlindhorst.imapmcp.core.tenancy.TenantId;
import jakarta.mail.AuthenticationFailedException;
import jakarta.mail.MessagingException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** SPEC 5.3 "Re-link": the pool reports every real LOGIN result with the revision it used. */
class LoginOutcomesTest {

    static final TenantId TENANT = new TenantId("t1");
    static final AccountId ID = new AccountId("a");

    final List<String> events = new ArrayList<>();
    final LoginOutcomes outcomes = new LoginOutcomes() {
        @Override
        public void refused(TenantId tenant, AccountId account, long revision) {
            events.add("refused:" + tenant.value() + ":" + account.value() + ":" + revision);
        }

        @Override
        public void succeeded(TenantId tenant, AccountId account, long revision) {
            events.add("succeeded:" + tenant.value() + ":" + account.value() + ":" + revision);
        }
    };
    GreenMail greenMail;
    ImapSessionFactory sessions;
    StorePool pool;

    @BeforeEach
    void setUp() {
        greenMail = new GreenMail(ServerSetupTest.IMAPS.dynamicPort());
        greenMail.start();
        greenMail.setUser("u@localhost", "u", "right");
        sessions = new ImapSessionFactory(ImapTimeouts.DEFAULTS, p -> {
            p.put("mail.imaps.ssl.trust", "*");
            p.put("mail.imaps.ssl.checkserveridentity", "false");
            return p;
        }, true);
    }

    @AfterEach
    void tearDown() {
        if (pool != null) {
            pool.close();
        }
        sessions.close();
        greenMail.stop();
    }

    MailAccount account(int port, String user, MailAccount.Status status, long revision) {
        return new MailAccount(ID, "l", null, "127.0.0.1", port, Security.IMPLICIT_TLS, Flavor.GENERIC, user,
                new SecretRef("env:X"), false, false, MailAccount.Auth.PASSWORD, status, revision);
    }

    PoolKey borrowWith(MailAccount account, String password) throws Exception {
        PoolKey key = PoolKey.of(new AccountContext(TENANT), new ResolvedAccount(TENANT, account, "acc_x"));
        pool = new StorePool(sessions, (t, a) -> Optional.of(new Secret(password.toCharArray())),
                new CapabilityCache(), k -> account, PoolSettings.DEFAULTS,
                de.alexanderlindhorst.imapmcp.core.metrics.MetricsRecorder.NOOP, outcomes);
        return key;
    }

    @Test
    void aRefusedLoginIsReportedWithTheRevisionTheLoginUsed() throws Exception {
        MailAccount a = account(greenMail.getImaps().getPort(), "u", MailAccount.Status.ACTIVE, 5);
        PoolKey key = borrowWith(a, "wrong");
        assertThatThrownBy(() -> pool.borrow(key)).hasRootCauseInstanceOf(AuthenticationFailedException.class);
        assertThat(events).containsExactly("refused:t1:a:5");
    }

    @Test
    void aSuccessfulLoginIsReportedOnceAndAReusedStoreIsNot() throws Exception {
        MailAccount a = account(greenMail.getImaps().getPort(), "u", MailAccount.Status.ACTIVE, 7);
        PoolKey key = borrowWith(a, "right");
        var first = pool.borrow(key);
        pool.release(key, first);
        var second = pool.borrow(key);
        pool.release(key, second);
        assertThat(events).containsExactly("succeeded:t1:a:7");
    }

    @Test
    void anUnreachableServerReportsNothing() throws Exception {
        MailAccount a = account(1, "u", MailAccount.Status.ACTIVE, 1);
        PoolKey key = borrowWith(a, "right");
        assertThatThrownBy(() -> pool.borrow(key)).isInstanceOf(Exception.class);
        assertThat(events).isEmpty();
    }

    @Test
    void anAccountThatNeedsARelinkIsNeverConnectedNorReported() throws Exception {
        MailAccount a = account(greenMail.getImaps().getPort(), "u", MailAccount.Status.NEEDS_RELINK, 2);
        PoolKey key = borrowWith(a, "right");
        assertThatThrownBy(() -> pool.borrow(key)).isInstanceOf(MessagingException.class);
        assertThat(events).isEmpty();
        assertThat(greenMail.getManagers().getImapHostManager()).isNotNull();
    }

    @Test
    void aFailingOutcomeSinkNeverChangesTheConnectResult() throws Exception {
        LoginOutcomes broken = new LoginOutcomes() {
            @Override
            public void refused(TenantId t, AccountId a, long r) {
                throw new IllegalStateException("db down");
            }

            @Override
            public void succeeded(TenantId t, AccountId a, long r) {
                throw new IllegalStateException("db down");
            }
        };
        MailAccount ok = account(greenMail.getImaps().getPort(), "u", MailAccount.Status.ACTIVE, 1);
        PoolKey key = PoolKey.of(new AccountContext(TENANT), new ResolvedAccount(TENANT, ok, "acc_x"));
        pool = new StorePool(sessions, (t, a) -> Optional.of(new Secret("right".toCharArray())), new CapabilityCache(),
                k -> ok, PoolSettings.DEFAULTS, de.alexanderlindhorst.imapmcp.core.metrics.MetricsRecorder.NOOP,
                broken);
        var store = pool.borrow(key);
        assertThat(store.isConnected()).isTrue();
        pool.release(key, store);
    }

    @Test
    void onlyRefusalsWithoutUnavailableCount() {
        assertThat(StorePoolFactory.isCountedRefusal(new AuthenticationFailedException("Invalid credentials"))).isTrue();
        assertThat(StorePoolFactory.isCountedRefusal(new AuthenticationFailedException(null))).isTrue();
        assertThat(StorePoolFactory.isCountedRefusal(
                new AuthenticationFailedException("[UNAVAILABLE] Temporary authentication failure"))).isFalse();
        assertThat(StorePoolFactory.isCountedRefusal(
                new AuthenticationFailedException("[unavailable] try later"))).isFalse();
        assertThat(StorePoolFactory.isCountedRefusal(
                new MessagingException("wrapped", new AuthenticationFailedException("NO bad")))).isTrue();
        assertThat(StorePoolFactory.isCountedRefusal(new MessagingException("connection failure"))).isFalse();
        assertThat(StorePoolFactory.isCountedRefusal(new java.net.ConnectException("refused"))).isFalse();
        assertThat(StorePoolFactory.isCountedRefusal(new RuntimeException("x"))).isFalse();
    }
}
