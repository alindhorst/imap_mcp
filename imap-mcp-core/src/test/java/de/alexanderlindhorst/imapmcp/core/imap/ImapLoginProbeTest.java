package de.alexanderlindhorst.imapmcp.core.imap;

import static org.assertj.core.api.Assertions.assertThat;

import com.icegreen.greenmail.util.GreenMail;
import com.icegreen.greenmail.util.ServerSetupTest;
import de.alexanderlindhorst.imapmcp.core.account.MailAccount;
import de.alexanderlindhorst.imapmcp.core.account.SecretRef;
import de.alexanderlindhorst.imapmcp.core.net.PinnedSocketFactory;
import de.alexanderlindhorst.imapmcp.core.net.SsrfPolicy;
import de.alexanderlindhorst.imapmcp.core.tenancy.AccountId;
import java.net.InetAddress;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** The link-time probe against GreenMail, through the pinned socket factory (C2, SPEC 5.3). */
class ImapLoginProbeTest {

    private static final String NAME = "imap.example.test";

    private GreenMail greenMail;
    private ImapSessionFactory factory;
    private final AtomicInteger lookups = new AtomicInteger();
    private volatile byte[] address = {127, 0, 0, 1};

    @BeforeEach
    void start() {
        greenMail = new GreenMail(ServerSetupTest.IMAPS.dynamicPort());
        greenMail.start();
        greenMail.setUser("user@localhost", "user", "pw");
        int port = greenMail.getImaps().getPort();
        PinnedSocketFactory sockets = new PinnedSocketFactory(SsrfPolicy.of(List.of(), true, List.of()), h -> {
            lookups.incrementAndGet();
            return new InetAddress[] {InetAddress.getByAddress(h, address)};
        }, a -> a.getAddress()[0] == 10, p -> p == port);
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

    private MailAccount account(int port, String user) {
        return new MailAccount(new AccountId("probe"), "probe", null, NAME, port, MailAccount.Security.IMPLICIT_TLS,
                MailAccount.Flavor.GENERIC, user, new SecretRef("db:"), true, false);
    }

    @Test
    void aCorrectLoginIsOkAndTheSessionEndsWithLogout() {
        ImapLoginProbe probe = new ImapLoginProbe(factory);

        assertThat(probe.probe(account(greenMail.getImaps().getPort(), "user"), "pw".toCharArray()))
                .isEqualTo(LoginProbe.Result.OK);
        assertThat(lookups.get()).isEqualTo(1); // resolved once, by the pinned factory
    }

    @Test
    void aRefusedLoginIsAuthFailed() {
        assertThat(new ImapLoginProbe(factory).probe(account(greenMail.getImaps().getPort(), "user"),
                "wrong".toCharArray())).isEqualTo(LoginProbe.Result.AUTH_FAILED);
    }

    @Test
    void aBlockedAddressIsUnreachableWithoutAnyConnection() {
        address = new byte[] {10, 0, 0, 5};

        assertThat(new ImapLoginProbe(factory).probe(account(greenMail.getImaps().getPort(), "user"),
                "pw".toCharArray())).isEqualTo(LoginProbe.Result.UNREACHABLE);
    }

    @Test
    void aClosedPortIsUnreachable() {
        int port = greenMail.getImaps().getPort();
        greenMail.stop();

        assertThat(new ImapLoginProbe(factory).probe(account(port, "user"), "pw".toCharArray()))
                .isEqualTo(LoginProbe.Result.UNREACHABLE);
        greenMail = new GreenMail(ServerSetupTest.IMAPS.dynamicPort()); // so that stop() has something to stop
        greenMail.start();
    }

    @Test
    void theProbeSessionsAreNotCached() {
        ImapLoginProbe probe = new ImapLoginProbe(factory);
        MailAccount a = account(greenMail.getImaps().getPort(), "user");

        assertThat(factory.probeSession(a)).isNotSameAs(factory.probeSession(a));
        assertThat(probe.probe(a, "pw".toCharArray())).isEqualTo(LoginProbe.Result.OK);
    }

    @Test
    void aFactoryWithoutThePinnedSocketFactoryIsRefusedAtConstruction() {
        try (ImapSessionFactory plain = new ImapSessionFactory(ImapTimeouts.DEFAULTS)) {
            org.assertj.core.api.Assertions.assertThatThrownBy(() -> new ImapLoginProbe(plain))
                    .isInstanceOf(IllegalArgumentException.class);
        }
        assertThat(factory.pinsSockets()).isTrue();
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.CsvSource(delimiter = '|', value = {
            "[UNAVAILABLE] Temporary authentication failure|UNREACHABLE", "  [unavailable] try later|UNREACHABLE",
            "[AUTHENTICATIONFAILED] Invalid credentials|AUTH_FAILED", "Invalid credentials|AUTH_FAILED"})
    void aLoginAnsweredNoUnavailableIsUnreachableNotRefusedCredentials(String answer, LoginProbe.Result expected)
            throws Exception {
        try (var proxy = new de.alexanderlindhorst.imapmcp.core.testsupport.FaultingImapProxy(
                greenMail.getImaps().getPort())) {
            proxy.rejectWithNo("LOGIN", answer);
            int port = proxy.port();
            PinnedSocketFactory sockets = new PinnedSocketFactory(SsrfPolicy.of(List.of(), true, List.of()),
                    h -> new InetAddress[] {InetAddress.getByAddress(h, address)}, a -> a.getAddress()[0] == 10,
                    p -> p == port);
            try (ImapSessionFactory viaProxy = new ImapSessionFactory(ImapTimeouts.DEFAULTS, p -> {
                p.put("mail.imaps.ssl.trust", "*");
                p.put("mail.imaps.ssl.checkserveridentity", "false");
                return p;
            }, false, sockets)) {
                assertThat(new ImapLoginProbe(viaProxy).probe(account(port, "user"), "pw".toCharArray()))
                        .isEqualTo(expected);
            }
        }
    }
}
