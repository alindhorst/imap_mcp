package de.alexanderlindhorst.imapmcp.core.imap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.icegreen.greenmail.junit5.GreenMailExtension;
import com.icegreen.greenmail.util.ServerSetupTest;
import de.alexanderlindhorst.imapmcp.core.account.MailAccount;
import de.alexanderlindhorst.imapmcp.core.account.MailAccount.Auth;
import de.alexanderlindhorst.imapmcp.core.account.MailAccount.Flavor;
import de.alexanderlindhorst.imapmcp.core.account.MailAccount.Security;
import de.alexanderlindhorst.imapmcp.core.account.MailAccount.Status;
import de.alexanderlindhorst.imapmcp.core.account.SecretRef;
import de.alexanderlindhorst.imapmcp.core.error.ErrorCode;
import de.alexanderlindhorst.imapmcp.core.error.ImapErrorMapper;
import de.alexanderlindhorst.imapmcp.core.tenancy.AccountId;
import de.alexanderlindhorst.imapmcp.core.tenancy.TenantId;
import de.alexanderlindhorst.imapmcp.core.testsupport.FakeTokens;
import de.alexanderlindhorst.imapmcp.core.testsupport.ScriptedImapsServer;
import de.alexanderlindhorst.imapmcp.core.testsupport.ScriptedImapsServer.AuthAnswer;
import jakarta.mail.AuthenticationFailedException;
import jakarta.mail.Session;
import java.time.Instant;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

/** The XOAUTH2 connect path (SPEC 5.5): token as password, guard, retry once, no counting of refusals. */
class Xoauth2ConnectorTest {

    private static final TenantId TENANT = new TenantId("t1");
    private static final String USER = "user@example.org";
    private static final Instant EXPIRES = Instant.now().plusSeconds(3600);
    private static final Instant DEADLINE = Instant.now().plusSeconds(60);

    @RegisterExtension
    static final GreenMailExtension GREEN_MAIL = new GreenMailExtension(ServerSetupTest.IMAPS.dynamicPort());

    private final ImapSessionFactory sessions = new ImapSessionFactory(ImapTimeouts.DEFAULTS, p -> {
        p.put("mail.imaps.ssl.trust", "*");
        p.put("mail.imaps.ssl.checkserveridentity", "false");
        return p;
    });

    @AfterEach
    void close() {
        sessions.close();
    }

    private static MailAccount account(int port) {
        return new MailAccount(new AccountId("a"), "label", USER, "127.0.0.1", port, Security.IMPLICIT_TLS,
                Flavor.GENERIC, USER, new SecretRef("db:"), false, false, Auth.XOAUTH2, Status.ACTIVE, 0L);
    }

    private Xoauth2Connector.Connected connect(MailAccount a, FakeTokens tokens) throws Exception {
        Session session = sessions.xoauth2ProbeSession(a);
        return Xoauth2Connector.connect(session, a, TENANT, tokens, DEADLINE);
    }

    @Test
    void connectsWithTheTokenAsPasswordAndReportsItsExpiry() throws Exception {
        GREEN_MAIL.setUser(USER, USER, "tok-1");
        FakeTokens tokens = new FakeTokens("tok-1", EXPIRES);
        MailAccount a = account(GREEN_MAIL.getImaps().getPort());
        Xoauth2Connector.Connected c = connect(a, tokens);
        try {
            assertThat(c.store().isConnected()).isTrue();
            assertThat(c.tokenExpiresAt()).isEqualTo(EXPIRES);
            assertThat(tokens.renewals()).isZero();
            assertThat(tokens.tenantsSeen()).containsOnly(TENANT);
        } finally {
            c.store().close();
        }
    }

    @Test
    void retriesOnceWithTheRenewedTokenAfterARejection() throws Exception {
        GREEN_MAIL.setUser(USER, USER, "tok-new"); // the first token is already revoked
        FakeTokens tokens = new FakeTokens("tok-old", EXPIRES).renewedTo("tok-new", EXPIRES.plusSeconds(1));
        Xoauth2Connector.Connected c = connect(account(GREEN_MAIL.getImaps().getPort()), tokens);
        try {
            assertThat(c.store().isConnected()).isTrue();
            assertThat(tokens.renewals()).isEqualTo(1);
            assertThat(c.tokenExpiresAt()).isEqualTo(EXPIRES.plusSeconds(1));
        } finally {
            c.store().close();
        }
    }

    @Test
    void aSecondRefusalIsXoauth2RejectedAndNotACredentialRefusal() {
        GREEN_MAIL.setUser(USER, USER, "something-else");
        FakeTokens tokens = new FakeTokens("tok-old", EXPIRES).renewedTo("tok-new", EXPIRES);
        assertThatThrownBy(() -> connect(account(GREEN_MAIL.getImaps().getPort()), tokens))
                .isInstanceOf(Xoauth2RejectedException.class)
                .satisfies(e -> {
                    assertThat(LoginRefusal.isCredentialRefusal(e)).isFalse(); // no failed_logins, no needs_relink
                    assertThat(e).hasNoCause();
                    var mapped = ImapErrorMapper.map(e, "rid").error();
                    assertThat(mapped.code()).isEqualTo(ErrorCode.AUTH_FAILED);
                    assertThat(mapped.details()).containsEntry("reason", "xoauth2_rejected");
                });
        assertThat(tokens.renewals()).isEqualTo(1);
    }

    @Test
    void aServerWithoutXoauth2GetsNeitherLoginNorAuthenticate() throws Exception {
        try (ScriptedImapsServer server = new ScriptedImapsServer("IMAP4rev1", AuthAnswer.OK)) {
            FakeTokens tokens = new FakeTokens("tok-secret-canary", EXPIRES).renewedTo("x", EXPIRES);
            assertThatThrownBy(() -> connect(account(server.port()), tokens))
                    .isInstanceOf(Xoauth2RejectedException.class);
            assertThat(server.received()).noneMatch(l -> l.toUpperCase().contains("LOGIN")
                    || l.toUpperCase().contains("AUTHENTICATE") || l.contains("tok-secret-canary"));
            assertThat(tokens.renewals()).isZero(); // no wire attempt, so no renewal
        }
    }

    @Test
    void unavailableIsNotRetriedAndStaysALoginUnavailable() throws Exception {
        try (ScriptedImapsServer server = new ScriptedImapsServer("IMAP4rev1 SASL-IR AUTH=XOAUTH2",
                AuthAnswer.UNAVAILABLE)) {
            FakeTokens tokens = new FakeTokens("tok", EXPIRES).renewedTo("x", EXPIRES);
            assertThatThrownBy(() -> connect(account(server.port()), tokens))
                    .isInstanceOf(AuthenticationFailedException.class)
                    .satisfies(e -> assertThat(LoginRefusal.isUnavailable(e)).isTrue());
            assertThat(tokens.renewals()).isZero();
        }
    }

    @Test
    void theErrorChallengeIsAnsweredWithAnEmptyLineAndCountsAsARefusal() throws Exception {
        try (ScriptedImapsServer server = new ScriptedImapsServer("IMAP4rev1 SASL-IR AUTH=XOAUTH2",
                AuthAnswer.CHALLENGE_THEN_NO)) {
            FakeTokens tokens = new FakeTokens("tok", EXPIRES).renewedTo("tok2", EXPIRES);
            assertThatThrownBy(() -> connect(account(server.port()), tokens))
                    .isInstanceOf(Xoauth2RejectedException.class);
            assertThat(tokens.renewals()).isEqualTo(1);
            assertThat(server.received()).contains(""); // the empty client line after the challenge
        }
    }
}
