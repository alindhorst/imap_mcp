package de.alexanderlindhorst.imapmcp.core.imap;

import static org.assertj.core.api.Assertions.assertThat;

import com.icegreen.greenmail.util.GreenMail;
import com.icegreen.greenmail.util.ServerSetupTest;
import de.alexanderlindhorst.imapmcp.core.account.MailAccount;
import de.alexanderlindhorst.imapmcp.core.account.SecretRef;
import de.alexanderlindhorst.imapmcp.core.net.PinnedSocketFactory;
import de.alexanderlindhorst.imapmcp.core.net.SsrfPolicy;
import de.alexanderlindhorst.imapmcp.core.tenancy.AccountId;
import de.alexanderlindhorst.imapmcp.core.testsupport.ScriptedImapsServer;
import de.alexanderlindhorst.imapmcp.core.testsupport.ScriptedImapsServer.AuthAnswer;
import java.net.InetAddress;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** The XOAUTH2 variant of the link-time probe (SPEC 5.3, 5.5), through the pinned socket factory. */
class ImapLoginProbeXoauth2Test {

    private static final String NAME = "imap.example.test";

    private GreenMail greenMail;
    private ImapSessionFactory factory;

    @BeforeEach
    void start() {
        greenMail = new GreenMail(ServerSetupTest.IMAPS.dynamicPort());
        greenMail.start();
        greenMail.setUser("user@example.org", "user@example.org", "tok-ok");
        PinnedSocketFactory sockets = new PinnedSocketFactory(SsrfPolicy.of(List.of(), true, List.of()),
                h -> new InetAddress[] {InetAddress.getByAddress(h, new byte[] {127, 0, 0, 1})},
                a -> a.getAddress()[0] == 10, p -> true);
        factory = new ImapSessionFactory(ImapTimeouts.DEFAULTS, p -> {
            p.put("mail.imaps.ssl.trust", "*");
            p.put("mail.imaps.ssl.checkserveridentity", "false");
            return p;
        }, false, sockets);
    }

    @AfterEach
    void stop() {
        factory.close();
        greenMail.stop();
    }

    private static MailAccount account(int port) { // a password-kind record: the probe forces XOAUTH2 anyway
        return new MailAccount(new AccountId("probe"), "probe", null, NAME, port, MailAccount.Security.IMPLICIT_TLS,
                MailAccount.Flavor.GENERIC, "user@example.org", new SecretRef("db:"), true, false);
    }

    @Test
    void aValidTokenIsOk() {
        assertThat(new ImapLoginProbe(factory).probeXoauth2(account(greenMail.getImaps().getPort()),
                "tok-ok".toCharArray())).isEqualTo(LoginProbe.Result.OK);
    }

    @Test
    void aRefusedTokenIsAuthFailedAndIsNotRetried() {
        assertThat(new ImapLoginProbe(factory).probeXoauth2(account(greenMail.getImaps().getPort()),
                "tok-bad".toCharArray())).isEqualTo(LoginProbe.Result.AUTH_FAILED);
    }

    @Test
    void aServerWithoutXoauth2IsAuthFailedWithoutAnyAuthenticationCommand() throws Exception {
        try (ScriptedImapsServer server = new ScriptedImapsServer("IMAP4rev1", AuthAnswer.OK)) {
            assertThat(new ImapLoginProbe(factory).probeXoauth2(account(server.port()), "tok-ok".toCharArray()))
                    .isEqualTo(LoginProbe.Result.AUTH_FAILED);
            assertThat(server.received()).noneMatch(l -> l.toUpperCase().contains("LOGIN")
                    || l.toUpperCase().contains("AUTHENTICATE") || l.contains("tok-ok"));
        }
    }

    @Test
    void anUnavailableAnswerIsUnreachable() throws Exception {
        try (ScriptedImapsServer server = new ScriptedImapsServer("IMAP4rev1 SASL-IR AUTH=XOAUTH2",
                AuthAnswer.UNAVAILABLE)) {
            assertThat(new ImapLoginProbe(factory).probeXoauth2(account(server.port()), "tok-ok".toCharArray()))
                    .isEqualTo(LoginProbe.Result.UNREACHABLE);
        }
    }

    @Test
    void thePasswordProbeOfTheSameClassIsUnchanged() {
        greenMail.setUser("user@localhost", "user", "pw");
        assertThat(new ImapLoginProbe(factory).probe(account(greenMail.getImaps().getPort()), "pw".toCharArray()))
                .isNotNull();
    }
}
