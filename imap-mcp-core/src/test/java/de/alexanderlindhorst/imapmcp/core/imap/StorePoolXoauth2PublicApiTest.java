package de.alexanderlindhorst.imapmcp.core.imap;

import static org.assertj.core.api.Assertions.assertThat;

import com.icegreen.greenmail.util.GreenMail;
import com.icegreen.greenmail.util.ServerSetupTest;
import de.alexanderlindhorst.imapmcp.core.account.LoginOutcomes;
import de.alexanderlindhorst.imapmcp.core.account.MailAccount;
import de.alexanderlindhorst.imapmcp.core.account.MailAccount.Auth;
import de.alexanderlindhorst.imapmcp.core.account.MailAccount.Flavor;
import de.alexanderlindhorst.imapmcp.core.account.MailAccount.Security;
import de.alexanderlindhorst.imapmcp.core.account.MailAccount.Status;
import de.alexanderlindhorst.imapmcp.core.account.PoolKey;
import de.alexanderlindhorst.imapmcp.core.account.ResolvedAccount;
import de.alexanderlindhorst.imapmcp.core.account.SecretRef;
import de.alexanderlindhorst.imapmcp.core.imap.pool.PoolSettings;
import de.alexanderlindhorst.imapmcp.core.imap.pool.StorePool;
import de.alexanderlindhorst.imapmcp.core.metrics.MetricsRecorder;
import de.alexanderlindhorst.imapmcp.core.tenancy.AccountContext;
import de.alexanderlindhorst.imapmcp.core.tenancy.AccountId;
import de.alexanderlindhorst.imapmcp.core.tenancy.TenantId;
import de.alexanderlindhorst.imapmcp.core.testsupport.FakeTokens;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import org.eclipse.angus.mail.imap.IMAPStore;
import org.junit.jupiter.api.Test;

/** The wiring API the app uses (from another package): a pool with a token source and a clock. */
class StorePoolXoauth2PublicApiTest {

    @Test
    void aPoolBuiltThroughThePublicApiConnectsAnXoauth2Account() throws Exception {
        TenantId tenant = new TenantId("t1");
        GreenMail gm = new GreenMail(ServerSetupTest.IMAPS.dynamicPort());
        gm.start();
        ImapSessionFactory sessions = new ImapSessionFactory(ImapTimeouts.DEFAULTS, p -> {
            p.put("mail.imaps.ssl.trust", "*");
            p.put("mail.imaps.ssl.checkserveridentity", "false");
            return p;
        });
        try {
            gm.setUser("u@example.org", "u@example.org", "tok");
            MailAccount a = new MailAccount(new AccountId("a"), "l", null, "127.0.0.1", gm.getImaps().getPort(),
                    Security.IMPLICIT_TLS, Flavor.GENERIC, "u@example.org", new SecretRef("db:"), false, false,
                    Auth.XOAUTH2, Status.ACTIVE, 0L);
            FakeTokens tokens = new FakeTokens("tok", Instant.now().plus(Duration.ofHours(1)));
            try (StorePool pool = new StorePool(sessions, (t, id) -> Optional.empty(), new CapabilityCache(),
                    k -> a, PoolSettings.DEFAULTS, MetricsRecorder.NOOP, LoginOutcomes.NONE, tokens,
                    Clock.systemUTC())) {
                PoolKey key = PoolKey.of(new AccountContext(tenant), new ResolvedAccount(tenant, a, "acc"));
                IMAPStore store = pool.borrow(key);
                assertThat(store.isConnected()).isTrue();
                pool.release(key, store);
                assertThat(tokens.currentCalls()).isEqualTo(1);
            }
        } finally {
            sessions.close();
            gm.stop();
        }
    }
}
