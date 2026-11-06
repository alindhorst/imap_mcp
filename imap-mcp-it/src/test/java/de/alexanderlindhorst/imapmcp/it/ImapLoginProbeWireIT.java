package de.alexanderlindhorst.imapmcp.it;

import static org.assertj.core.api.Assertions.assertThat;

import de.alexanderlindhorst.imapmcp.core.account.MailAccount;
import de.alexanderlindhorst.imapmcp.core.account.SecretRef;
import de.alexanderlindhorst.imapmcp.core.imap.ImapLoginProbe;
import de.alexanderlindhorst.imapmcp.core.imap.ImapSessionFactory;
import de.alexanderlindhorst.imapmcp.core.imap.ImapTimeouts;
import de.alexanderlindhorst.imapmcp.core.imap.LoginProbe;
import de.alexanderlindhorst.imapmcp.core.net.PinnedSocketFactory;
import de.alexanderlindhorst.imapmcp.core.net.SsrfPolicy;
import de.alexanderlindhorst.imapmcp.core.tenancy.AccountId;
import java.net.InetAddress;
import java.util.List;
import org.junit.jupiter.api.Test;

/** What the link-time probe puts on the wire (C2, SPEC 5.3): LOGIN, CAPABILITY and LOGOUT, never a folder. */
class ImapLoginProbeWireIT {

    @Test
    void theProbeSendsLoginCapabilityAndLogoutAndNeverSelectsOrExaminesAFolder() throws Exception {
        try (GreenMailFixture imap = GreenMailFixture.start()) {
            imap.createUser("user@localhost", "user", "pw");
            int port = imap.imapsPort();
            PinnedSocketFactory sockets = new PinnedSocketFactory(SsrfPolicy.of(List.of(), true, List.of()),
                    h -> new InetAddress[] {InetAddress.getByAddress(h, new byte[] {127, 0, 0, 1})}, a -> false,
                    p -> p == port);
            TraceTap tap = new TraceTap().attach();
            try (ImapSessionFactory factory = new ImapSessionFactory(ImapTimeouts.DEFAULTS, p -> {
                p.put("mail.imaps.ssl.trust", "*");
                p.put("mail.imaps.ssl.checkserveridentity", "false");
                return p;
            }, true, sockets)) {
                MailAccount account = new MailAccount(new AccountId("probe"), "probe", null, "imap.example.test",
                        port, MailAccount.Security.IMPLICIT_TLS, MailAccount.Flavor.GENERIC, "user",
                        new SecretRef("db:"), true, false);

                LoginProbe.Result result = new ImapLoginProbe(factory).probe(account, "pw".toCharArray());

                assertThat(result).isEqualTo(LoginProbe.Result.OK);
            } finally {
                tap.detach();
            }

            List<String> commands = tap.all();
            assertThat(commands).anyMatch(c -> c.startsWith("LOGIN") || c.startsWith("AUTHENTICATE"));
            assertThat(commands).anyMatch(c -> c.startsWith("CAPABILITY") || c.startsWith("LOGIN"));
            assertThat(commands).anyMatch(c -> c.startsWith("LOGOUT"));
            assertThat(commands).noneMatch(c -> c.startsWith("SELECT") || c.startsWith("EXAMINE")
                    || c.startsWith("LIST") || c.startsWith("STATUS") || c.startsWith("FETCH")
                    || c.startsWith("UID") || c.startsWith("SEARCH") || c.startsWith("CREATE")
                    || c.startsWith("DELETE") || c.startsWith("APPEND") || c.startsWith("NAMESPACE"));
        }
    }
}
