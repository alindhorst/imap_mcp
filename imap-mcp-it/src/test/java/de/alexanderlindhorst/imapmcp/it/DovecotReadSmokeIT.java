package de.alexanderlindhorst.imapmcp.it;

import java.io.IOException;
import java.nio.file.Files;
import java.util.List;
import java.util.Optional;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Dry run of the smoke read checks (#258): the very same {@link AbstractReadSmoke} code, config builder and
 * {@code env:} reference, only pointed at the Dovecot fixture, so the harness is known to work before a real account
 * is available. Runs in the normal build (it is an IT); the live variants run only under {@code -Psmoke}.
 */
@Testcontainers
class DovecotReadSmokeIT extends AbstractReadSmoke {

    private static final String USER = "smoke-read";

    @Container
    static final DovecotContainer DOVECOT = new DovecotContainer();

    @Override
    Optional<SmokeEnv.Settings> settings() {
        try (MailSeeder seeder = DOVECOT.seeder(USER)) {
            seeder.appendPlain("INBOX", "dry run", "body");
            SmokeEnv.Settings s = new SmokeEnv.Settings(SmokeEnv.Provider.GMAIL, USER, DovecotContainer.PASSWORD,
                    DOVECOT.getHost(), DOVECOT.imapsPort(), null);
            return Optional.of(s.withLocalFixture(DOVECOT.getHost(), DOVECOT.imapsPort(),
                    TestTrust.writeTruststore(Files.createTempDirectory("smoke-trust"))));
        } catch (Exception e) {
            throw new IllegalStateException("cannot prepare the Dovecot fixture", e);
        }
    }

    @Override
    String skipReason() {
        return "never skipped";
    }

    @Override
    List<String> expectedRoles() {
        return List.of("sent", "drafts", "trash", "junk");
    }
}
