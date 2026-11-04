package de.alexanderlindhorst.imapmcp.it;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * The {@code NO_UTF8_ACCEPT} Dovecot profile (SPEC 5.8, #179) does what it claims: after login the server advertises
 * everything the full set does except {@code UTF8=ACCEPT}, so Angus has no reason to ENABLE it and mailbox names stay
 * in modified UTF-7. Seen on a raw connection, without Angus in between.
 */
@Testcontainers
class DovecotProfileIT {

    @Container
    static final DovecotContainer FULL = new DovecotContainer(DovecotContainer.Profile.FULL);
    @Container
    static final DovecotContainer NO_UTF8 = new DovecotContainer(DovecotContainer.Profile.NO_UTF8_ACCEPT);

    private static java.util.List<String> capabilities(DovecotContainer c) throws Exception {
        try (RawImap imap = new RawImap(c.getHost(), c.imapsPort())) {
            imap.login("caps", DovecotContainer.PASSWORD);
            return imap.capabilities();
        }
    }

    @Test
    void theFullSetAdvertisesUtf8Accept() throws Exception {
        assertThat(capabilities(FULL)).contains("UTF8=ACCEPT", "MOVE", "UIDPLUS", "SPECIAL-USE");
    }

    @Test
    void theNoUtf8AcceptProfileDropsOnlyThatCapability() throws Exception {
        assertThat(capabilities(NO_UTF8)).doesNotContain("UTF8=ACCEPT", "UTF8=ONLY")
                .contains("MOVE", "UIDPLUS", "SPECIAL-USE");
        assertThat(DovecotContainer.Profile.NO_UTF8_ACCEPT.utf8Accept()).isFalse();
        assertThat(DovecotContainer.Profile.FULL.utf8Accept()).isTrue();
    }
}
