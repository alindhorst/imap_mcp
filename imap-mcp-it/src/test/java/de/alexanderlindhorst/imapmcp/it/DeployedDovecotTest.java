package de.alexanderlindhorst.imapmcp.it;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

class DeployedDovecotTest {

    @Test
    void twoDistinctUsersWithLongGeneratedPasswords() {
        List<DeployedDovecot.ImapUser> users = DeployedDovecot.generateUsers();
        assertThat(users).hasSize(2);
        assertThat(users.get(0).name()).isNotEqualTo(users.get(1).name());
        assertThat(users.get(0).password().value()).isNotEqualTo(users.get(1).password().value())
                .hasSizeGreaterThanOrEqualTo(24);
        assertThat(DeployedDovecot.generateUsers().get(0).password().value())
                .isNotEqualTo(users.get(0).password().value());
    }

    @Test
    void thePasswordNeverShowsInToString() {
        DeployedDovecot.ImapUser user = DeployedDovecot.generateUsers().get(0);
        assertThat(user.toString()).doesNotContain(user.password().value());
        assertThat(user.password().toString()).doesNotContain(user.password().value());
    }

    @Test
    void passwdFileHasOneLinePerUserWithAPlainScheme() {
        List<DeployedDovecot.ImapUser> users = DeployedDovecot.generateUsers();
        String file = DeployedDovecot.passwdFile(users);
        for (DeployedDovecot.ImapUser u : users) {
            assertThat(file).contains(u.name() + ":{PLAIN}" + u.password().value() + "\n");
        }
    }

    @Test
    void configListensOnImplicitTlsPort993AndRequiresTlsWithThePasswdFile() {
        String conf = DeployedDovecot.config();
        assertThat(conf).contains("ssl = required").contains("port = 993").contains("inet_listener imaps")
                .contains("driver = passwd-file").contains("/etc/dovecot/users");
    }

    @Test
    void dockerfileUsesThePinnedImageAndCopiesTheFixtureFiles() {
        String dockerfile = DeployedDovecot.dockerfile();
        assertThat(dockerfile).startsWith("FROM " + ItImages.DOVECOT + "\n");
        assertThat(dockerfile).contains("COPY tls.crt tls.key /etc/dovecot/ssl/")
                .contains("COPY users /etc/dovecot/users").contains("COPY seed/ /seed/")
                .doesNotContain("VOLUME");
    }

    @Test
    void leafExtensionCarriesOnlyTheSanOfTheImapHostForServerAuth() {
        assertThat(DeployedDovecot.leafExtension())
                .isEqualTo("subjectAltName=DNS:imap.imap-mcp.test\nextendedKeyUsage=serverAuth\n");
    }

    @Test
    void seedMessageCarriesTheCanarySubjectAndCrlfLineEndings() {
        String subject = DeployedDovecot.newCanarySubject();
        assertThat(subject).startsWith("canary-").isNotEqualTo(DeployedDovecot.newCanarySubject());
        String eml = DeployedDovecot.seedMessage(subject);
        assertThat(eml).contains("Subject: " + subject + "\r\n").contains("\r\n\r\n");
        assertThat(eml.replace("\r\n", "")).doesNotContain("\n");
    }
}
