package de.alexanderlindhorst.imapmcp.it;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import org.eclipse.angus.mail.imap.IMAPFolder;
import org.eclipse.angus.mail.imap.IMAPStore;
import org.junit.jupiter.api.Test;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/** Smoke test of the Dovecot fixture; also the check that {@code @Testcontainers} works on JUnit 6 (#51). */
@Testcontainers
class DovecotSmokeIT {

    @Container
    static final DovecotContainer DOVECOT = new DovecotContainer();

    @Test
    void advertisesPostLoginCapabilitiesTheServerNeeds() throws Exception {
        try (MailSeeder seeder = DOVECOT.seeder("alice")) {
            IMAPStore store = seeder.store();
            assertThat(store.hasCapability("SPECIAL-USE")).isTrue();
            assertThat(store.hasCapability("MOVE")).isTrue();
            assertThat(store.hasCapability("UIDPLUS")).isTrue();
            assertThat(store.hasCapability("SORT")).isTrue();
        }
    }

    @Test
    void preLoginGreetingOnTheStarttlsPortOffersStarttlsButNotTheMailboxCapabilities() throws Exception {
        String greeting = ImapProbe.greeting(DOVECOT.getHost(), DOVECOT.startTlsPort());
        assertThat(greeting).contains("STARTTLS").doesNotContain("MOVE").doesNotContain("SPECIAL-USE");
    }

    @Test
    void specialUseMailboxesExistOutOfTheBox() throws Exception {
        try (MailSeeder seeder = DOVECOT.seeder("alice")) {
            List<String> attributes = new ArrayList<>();
            for (var f : seeder.store().getDefaultFolder().list("*")) {
                attributes.addAll(List.of(((IMAPFolder) f).getAttributes()));
            }
            assertThat(attributes).contains("\\Sent", "\\Trash", "\\Drafts", "\\Junk", "\\Archive");
        }
    }

    @Test
    void seederAppendsAMessageAndItCanBeReadBackByUid() throws Exception {
        try (MailSeeder seeder = DOVECOT.seeder("alice")) {
            long uid = seeder.appendPlain("INBOX", "smoke subject", "marker-" + System.nanoTime());
            assertThat(uid).isPositive();
            assertThat(seeder.fetchRaw("INBOX", uid)).contains("smoke subject").contains("marker-");
        }
    }

    @Test
    void usersAreIsolatedFromEachOther() throws Exception {
        try (MailSeeder alice = DOVECOT.seeder("iso-alice"); MailSeeder bob = DOVECOT.seeder("iso-bob")) {
            alice.appendPlain("INBOX", "only alice", "x");
            assertThat(bob.messageCount("INBOX")).isZero();
            assertThat(alice.messageCount("INBOX")).isEqualTo(1);
        }
    }
}
