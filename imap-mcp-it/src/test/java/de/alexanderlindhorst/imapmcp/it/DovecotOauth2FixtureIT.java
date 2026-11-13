package de.alexanderlindhorst.imapmcp.it;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Proves the XOAUTH2 Dovecot fixtures over a raw socket, without application code (#492, SPEC 5.8): a Nimbus-signed
 * token is accepted, an expired token, a wrong signature and a wrong user are rejected, and the second profile
 * advertises no XOAUTH2 and rejects a JWT used as the LOGIN password.
 */
@Testcontainers
class DovecotOauth2FixtureIT {

    static final Xoauth2Jwt JWT = new Xoauth2Jwt();

    @Container
    static final DovecotOauth2Container XOAUTH2 =
            new DovecotOauth2Container(DovecotOauth2Container.Mode.XOAUTH2, JWT);

    @Container
    static final DovecotOauth2Container NONE = new DovecotOauth2Container(DovecotOauth2Container.Mode.NONE, JWT);

    private static String last(List<String> lines) {
        return lines.get(lines.size() - 1);
    }

    @Test
    void xoauth2ProfileAdvertisesTheMechanism() throws Exception {
        try (RawImapClient c = new RawImapClient(XOAUTH2.getHost(), XOAUTH2.imapsPort())) {
            assertThat(c.greeting).contains("AUTH=XOAUTH2");
        }
    }

    @Test
    void validTokenAuthenticates() throws Exception {
        try (RawImapClient c = new RawImapClient(XOAUTH2.getHost(), XOAUTH2.imapsPort())) {
            List<String> r = c.authenticateXoauth2("alice", JWT.token("alice", Duration.ofMinutes(5)));
            assertThat(last(r)).matches("t\\d+ OK .*");
            assertThat(last(c.command("SELECT INBOX"))).contains("OK");
        }
    }

    @Test
    void expiredTokenIsRejected() throws Exception {
        try (RawImapClient c = new RawImapClient(XOAUTH2.getHost(), XOAUTH2.imapsPort())) {
            List<String> r = c.authenticateXoauth2("alice", JWT.token("alice", Duration.ofSeconds(-10)));
            assertThat(last(r)).matches("t\\d+ NO .*");
        }
    }

    @Test
    void tokenSignedWithAnotherKeyIsRejected() throws Exception {
        try (RawImapClient c = new RawImapClient(XOAUTH2.getHost(), XOAUTH2.imapsPort())) {
            List<String> r = c.authenticateXoauth2("alice", JWT.tokenWithWrongKey("alice", Duration.ofMinutes(5)));
            assertThat(last(r)).matches("t\\d+ NO .*");
        }
    }

    @Test
    void tokenForAnotherUserIsRejected() throws Exception {
        try (RawImapClient c = new RawImapClient(XOAUTH2.getHost(), XOAUTH2.imapsPort())) {
            List<String> r = c.authenticateXoauth2("alice", JWT.token("bob", Duration.ofMinutes(5)));
            assertThat(last(r)).matches("t\\d+ NO .*");
        }
    }

    @Test
    void noXoauth2ProfileAdvertisesNoXoauth2AndRefusesTheMechanism() throws Exception {
        try (RawImapClient c = new RawImapClient(NONE.getHost(), NONE.imapsPort())) {
            assertThat(c.greeting).doesNotContain("XOAUTH2");
            List<String> r = c.authenticateXoauth2("alice", JWT.token("alice", Duration.ofMinutes(5)));
            assertThat(last(r)).matches("t\\d+ NO .*");
        }
    }

    @Test
    void noXoauth2ProfileRejectsAJwtAsLoginPassword() throws Exception {
        try (RawImapClient c = new RawImapClient(NONE.getHost(), NONE.imapsPort())) {
            String jwt = JWT.token("alice", Duration.ofMinutes(5));
            assertThat(last(c.command("LOGIN alice " + jwt))).matches("t\\d+ NO .*");
        }
        try (RawImapClient c = new RawImapClient(NONE.getHost(), NONE.imapsPort())) {
            assertThat(last(c.command("LOGIN alice " + DovecotOauth2Container.PASSWORD))).matches("t\\d+ OK .*");
        }
    }
}
