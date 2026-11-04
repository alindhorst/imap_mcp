package de.alexanderlindhorst.imapmcp.it;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Dry run of the smoke write plumbing (#258) against Dovecot: the same {@link SmokeWriteFixture} (labels created,
 * message appended, handles found by run id, copy and move through the shaded jar) and above all its cleanup, which
 * must leave neither folders nor foreign mail touched. The Gmail-specific label semantics cannot be checked here.
 */
@Testcontainers
class DovecotWriteFixtureSmokeIT {

    private static final String USER = "smoke-write";

    @Container
    static final DovecotContainer DOVECOT = new DovecotContainer();

    @Test
    void fixtureCreatesItsScopeUsesItAndRemovesItWithoutTouchingOtherMail() throws Exception {
        try (MailSeeder seeder = DOVECOT.seeder(USER)) {
            seeder.appendPlain("INBOX", "bystander", "must stay untouched");
        }
        SmokeEnv.Settings s = new SmokeEnv.Settings(SmokeEnv.Provider.GMAIL, USER, DovecotContainer.PASSWORD,
                DOVECOT.getHost(), DOVECOT.imapsPort(), null)
                .withLocalFixture(DOVECOT.getHost(), DOVECOT.imapsPort(),
                        TestTrust.writeTruststore(Files.createTempDirectory("smoke-trust")));
        String label;
        String label2;
        try (SmokeWriteFixture fx = new SmokeWriteFixture(s, List.of())) {
            label = fx.label;
            label2 = fx.label2;
            String h = fx.seed("one");
            SmokeClient.Result copy = fx.client.ok("copy_messages", "handles", List.of(h),
                    "target_folder", fx.label2);
            assertThat(copy.itemStatuses()).containsExactly("ok");
            assertThat(fx.find(fx.label2, "one")).hasSize(1);
            fx.seed("two");
        }
        try (MailSeeder seeder = DOVECOT.seeder(USER)) {
            assertThat(seeder.store().getFolder(label).exists()).isFalse();
            assertThat(seeder.store().getFolder(label2).exists()).isFalse();
            assertThat(seeder.messageCount("INBOX")).isEqualTo(1);
        }
    }
}
