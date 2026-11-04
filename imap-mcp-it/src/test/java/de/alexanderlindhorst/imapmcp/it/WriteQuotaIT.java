package de.alexanderlindhorst.imapmcp.it;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.testcontainers.utility.MountableFile;

/**
 * {@code [OVERQUOTA]} (#207, SPEC 5.4, 5.8): a Dovecot with a small storage quota refuses the COPY with
 * {@code NO [OVERQUOTA]}; copy and the copy step of the move fallback (NO_MOVE profile) map it to
 * {@code QUOTA_EXCEEDED}, and the source stays untouched (no \Deleted, no expunge). The mapping itself is also
 * covered at unit level (#178).
 */
class WriteQuotaIT {

    private static DovecotContainer dovecot;

    @TempDir
    Path dir;

    private WriteHarness harness;
    private TraceTap tap;

    @BeforeAll
    static void start() {
        dovecot = new DovecotContainer(DovecotContainer.Profile.NO_MOVE);
        dovecot.withCopyFileToContainer(MountableFile.forClasspathResource("dovecot/97-quota.conf", 0644),
                "/etc/dovecot/conf.d/97-quota.conf");
        dovecot.start();
    }

    @AfterAll
    static void stop() {
        dovecot.stop();
    }

    @AfterEach
    void cleanUp() {
        if (tap != null) {
            tap.detach();
        }
        if (harness != null) {
            harness.close();
        }
    }

    /** A message of about 24 KB: it fits once into the 40 KB quota, a second copy of it does not. */
    private static String bigBody() {
        return "quota padding line\n".repeat(1250);
    }

    private String handleOfSeededMessage(String user) throws Exception {
        try (MailSeeder seeder = dovecot.seeder(user)) {
            seeder.appendPlain("INBOX", "quota source", bigBody());
            seeder.appendPlain("QuotaTarget", "small", "x");
        }
        tap = new TraceTap().attach();
        harness = new WriteHarness(dir, dovecot.getHost(), dovecot.imapsPort(), user, DovecotContainer.PASSWORD);
        return harness.handles("INBOX").getFirst();
    }

    /** The refused group is a per-item failure (SPEC 4: from step 9 on, a result, never a whole-call error). */
    @SuppressWarnings("unchecked")
    private static void assertQuotaExceeded(WriteHarness.Result r) {
        assertThat(r.isError()).as("%s", r.structured()).isFalse();
        assertThat(r.statuses()).containsExactly("failed");
        assertThat((java.util.Map<String, Object>) r.items().getFirst().get("error")).containsEntry("code",
                "QUOTA_EXCEEDED").containsEntry("retryable", false);
    }

    private void assertSourceUntouchedAndNothingCopied(String user, MailboxSnapshot before) throws Exception {
        try (MailSeeder seeder = dovecot.seeder(user)) {
            MailboxSnapshot.Diff diff = MailboxSnapshot.diff(before, MailboxSnapshot.of(seeder.store(), "INBOX",
                    "QuotaTarget"));
            assertThat(diff.isEmpty()).as("source and target are unchanged").isTrue();
        }
    }

    @Test
    void copyOverQuotaIsQuotaExceededAndTheSourceIsUntouched() throws Exception {
        String user = "wquota1";
        String handle = handleOfSeededMessage(user);
        MailboxSnapshot before;
        try (MailSeeder seeder = dovecot.seeder(user)) {
            before = MailboxSnapshot.of(seeder.store(), "INBOX", "QuotaTarget");
        }

        WriteHarness.Result r = harness.callOn("copy_messages", "handles", List.of(handle), "target_folder",
                "QuotaTarget");

        assertQuotaExceeded(r);
        assertSourceUntouchedAndNothingCopied(user, before);
    }

    @Test
    void fallbackMoveOverQuotaIsQuotaExceededAndTheSourceIsUntouched() throws Exception {
        String user = "wquota2";
        String handle = handleOfSeededMessage(user);
        MailboxSnapshot before;
        try (MailSeeder seeder = dovecot.seeder(user)) {
            before = MailboxSnapshot.of(seeder.store(), "INBOX", "QuotaTarget");
        }

        WriteHarness.Result r = harness.callOn("move_messages", "handles", List.of(handle), "target_folder",
                "QuotaTarget");

        assertQuotaExceeded(r);
        assertThat(tap.all()).noneMatch(c -> c.startsWith("UID STORE") || c.contains("EXPUNGE"));
        assertSourceUntouchedAndNothingCopied(user, before);
    }
}
