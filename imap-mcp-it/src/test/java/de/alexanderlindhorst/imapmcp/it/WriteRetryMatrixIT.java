package de.alexanderlindhorst.imapmcp.it;

import static org.assertj.core.api.Assertions.assertThat;

import eu.rekawek.toxiproxy.model.ToxicDirection;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.testcontainers.containers.Network;

/**
 * Retry matrix (#207, SPEC 5.4, 5.5, 5.8): every bulk write tool is interrupted before the first write, with the
 * answer to the mutating command lost, and after the write was answered (the close fails), on Dovecot FULL and, for
 * the multi-command fallback, NO_MOVE (lost answer after COPY, after the flag STORE and after UID EXPUNGE). Then the
 * same call is repeated and the documented outcome and the mailbox state are asserted: never data loss; move/delete
 * {@code already_gone} or {@code ok}; copy duplicates; set_flags idempotent.
 *
 * <p>The cut is a Toxiproxy {@code limit_data} toxic (0 bytes, downstream) installed by a {@link TraceTap} step while
 * the triggering command is traced, i.e. before it is sent: the command still reaches the server (upstream is not
 * toxic) and is executed, while its answer never arrives. No timing involved. A connection failure after the first
 * mutating command is written yields a result with the group {@code unknown} (SPEC 5.5); the deadline variant
 * ({@code TIMEOUT} with outcome unknown, {@code OUTCOME_UNKNOWN} for copy) is covered in {@code WriteInterruptionIT}.
 */
class WriteRetryMatrixIT {

    private static final AtomicInteger USERS = new AtomicInteger();
    private static final String SUBJECT = "retry matrix";

    private static Network network;
    private static DovecotContainer full;
    private static DovecotContainer noMove;
    private static ToxiproxyFixture toxiproxy;
    private static ToxiproxyFixture.Proxy fullProxy;
    private static ToxiproxyFixture.Proxy noMoveProxy;

    @TempDir
    Path dir;

    private TraceTap tap;
    private WriteHarness harness;
    private ToxiproxyFixture.Proxy activeProxy;

    @BeforeAll
    static void start() throws Exception {
        network = Network.newNetwork();
        full = new DovecotContainer(DovecotContainer.Profile.FULL).withNetwork(network)
                .withNetworkAliases("dovecot-full");
        noMove = new DovecotContainer(DovecotContainer.Profile.NO_MOVE).withNetwork(network)
                .withNetworkAliases("dovecot-nomove");
        toxiproxy = new ToxiproxyFixture(network);
        full.start();
        noMove.start();
        toxiproxy.start();
        fullProxy = toxiproxy.proxyTo("retry-full", "dovecot-full", DovecotContainer.IMAPS_PORT);
        noMoveProxy = toxiproxy.proxyTo("retry-nomove", "dovecot-nomove", DovecotContainer.IMAPS_PORT);
    }

    @AfterAll
    static void stop() {
        toxiproxy.stop();
        noMove.stop();
        full.stop();
        network.close();
    }

    @AfterEach
    void cleanUp() throws Exception {
        if (activeProxy != null) {
            clearToxics(activeProxy);
        }
        if (tap != null) {
            tap.detach();
        }
        if (harness != null) {
            harness.close();
        }
    }

    private static void clearToxics(ToxiproxyFixture.Proxy proxy) throws Exception {
        for (var t : proxy.toxics().toxics().getAll()) {
            t.remove();
        }
    }

    // ---- scenario runner ----

    /** One interruption scenario: what is cut, what the first call reports and the state after it and the retry. */
    private record Scenario(String tool, boolean fallback, String cutAt, String first, int srcAfterFirst,
            int tgtAfterFirst, String retry, int srcAfterRetry, int tgtAfterRetry) {
    }

    private static final String ERROR = "error";

    private void run(Scenario s) throws Exception {
        boolean delete = s.tool().equals("delete_messages");
        boolean flags = s.tool().equals("set_flags");
        DovecotContainer container = s.fallback() ? noMove : full;
        activeProxy = s.fallback() ? noMoveProxy : fullProxy;
        String target = delete ? "Trash" : "Target";
        String user = "wretry" + USERS.incrementAndGet();
        try (MailSeeder seeder = container.seeder(user)) {
            seeder.appendPlain("INBOX", SUBJECT, "body");
            if (!delete) {
                seeder.appendPlain(target, "target seed", "body");
            }
        }
        tap = new TraceTap().attach();
        harness = new WriteHarness(dir, activeProxy.host(), activeProxy.port(), user, DovecotContainer.PASSWORD);
        String handle = harness.handles("INBOX").getFirst();
        // The Trash role folder holds nothing at first; the seeded target message of the other tools is subtracted.
        int base = delete ? 0 : 1;
        MailboxSnapshot before = snapshot(container, user, target);
        long createdBefore = harness.pool.createdCount();

        if (s.cutAt() != null) {
            tap.on(s.cutAt(), () -> activeProxy.toxics().toxics().limitData("cut", ToxicDirection.DOWNSTREAM, 0));
        }
        WriteHarness.Result first = invoke(s.tool(), handle, target);
        assertThat(tap.done()).as("the cut was installed at %s", s.cutAt()).isTrue();
        clearToxics(activeProxy);

        if (s.first().equals(ERROR)) {
            assertThat(first.isError()).as("%s", first.structured()).isTrue();
            assertThat(first.code()).as("whole-call error before any write").isIn("UPSTREAM_UNAVAILABLE", "TIMEOUT");
            assertThat(first.error().get("retryable")).isEqualTo(true);
        } else {
            assertThat(first.isError()).as("%s", first.structured()).isFalse();
            assertThat(first.statuses()).containsExactly(s.first());
        }
        MailboxSnapshot afterFirst = snapshot(container, user, target);
        assertThat(count(afterFirst, "INBOX")).as("source after the first call").isEqualTo(s.srcAfterFirst());
        assertThat(count(afterFirst, target) - base).as("target after the first call").isEqualTo(s.tgtAfterFirst());
        if (s.first().equals(ERROR)) {
            assertThat(MailboxSnapshot.diff(before, afterFirst).isEmpty()).as("nothing changed before the write").isTrue();
        }
        if (flags && !s.first().equals(ERROR)) {
            assertThat(afterFirst.folder("INBOX").flags().values()).allSatisfy(f -> assertThat(f).contains("\\Flagged"));
        }

        WriteHarness.Result retry = invoke(s.tool(), handle, target);
        assertThat(retry.isError()).as("%s", retry.structured()).isFalse();
        assertThat(retry.statuses()).containsExactly(s.retry());
        MailboxSnapshot afterRetry = snapshot(container, user, target);
        assertThat(count(afterRetry, "INBOX")).as("source after the retry").isEqualTo(s.srcAfterRetry());
        assertThat(count(afterRetry, target) - base).as("target after the retry").isEqualTo(s.tgtAfterRetry());
        // Never data loss: the message exists somewhere, and a repeat never touched anything else.
        assertThat(count(afterRetry, "INBOX") + count(afterRetry, target) - base).isGreaterThanOrEqualTo(1);
        assertThat(harness.pool.createdCount()).as("the broken connection was replaced").isGreaterThan(createdBefore);
        if (flags) {
            assertThat(afterRetry.folder("INBOX").flags()).as("set_flags is idempotent")
                    .allSatisfy((uid, f) -> assertThat(f).contains("\\Flagged"));
        }
    }

    private WriteHarness.Result invoke(String tool, String handle, String target) {
        return switch (tool) {
            case "move_messages", "copy_messages" -> harness.callOn(tool, "handles", List.of(handle),
                    "target_folder", target);
            case "delete_messages" -> harness.callOn(tool, "handles", List.of(handle));
            case "set_flags" -> harness.callOn(tool, "handles", List.of(handle), "flagged", true);
            default -> throw new IllegalArgumentException(tool);
        };
    }

    private static MailboxSnapshot snapshot(DovecotContainer c, String user, String target) throws Exception {
        try (MailSeeder seeder = c.seeder(user)) {
            return MailboxSnapshot.of(seeder.store(), "INBOX", target);
        }
    }

    private static int count(MailboxSnapshot s, String folder) {
        return s.folder(folder).flags().size();
    }

    // ---- move ----

    @Test
    void moveInterruptedBeforeTheWriteChangesNothingAndTheRetrySucceeds() throws Exception {
        run(new Scenario("move_messages", false, "UID FETCH", ERROR, 1, 0, "ok", 0, 1));
    }

    @Test
    void moveWithLostAnswerRetriesToAlreadyGone() throws Exception {
        run(new Scenario("move_messages", false, "UID MOVE", "unknown", 0, 1, "already_gone", 0, 1));
    }

    @Test
    void moveInterruptedAfterTheAnswerRetriesToAlreadyGone() throws Exception {
        run(new Scenario("move_messages", false, "UNSELECT", "ok", 0, 1, "already_gone", 0, 1));
    }

    @Test
    void fallbackMoveBeforeTheWriteChangesNothingAndTheRetrySucceeds() throws Exception {
        run(new Scenario("move_messages", true, "UID FETCH", ERROR, 1, 0, "ok", 0, 1));
    }

    @Test
    void fallbackMoveWithLostCopyAnswerLeavesSourceAndCopyAndTheRetryDuplicates() throws Exception {
        run(new Scenario("move_messages", true, "UID COPY", "unknown", 1, 1, "ok", 0, 2));
    }

    @Test
    void fallbackMoveWithLostStoreAnswerRetriesToOkWithADuplicate() throws Exception {
        run(new Scenario("move_messages", true, "UID STORE", "unknown", 1, 1, "ok", 0, 2));
    }

    @Test
    void fallbackMoveWithLostExpungeAnswerRetriesToAlreadyGone() throws Exception {
        run(new Scenario("move_messages", true, "UID EXPUNGE", "unknown", 0, 1, "already_gone", 0, 1));
    }

    @Test
    void fallbackMoveInterruptedAfterTheAnswerRetriesToAlreadyGone() throws Exception {
        run(new Scenario("move_messages", true, "UNSELECT", "ok", 0, 1, "already_gone", 0, 1));
    }

    // ---- delete (a move to the \Trash role folder) ----

    @Test
    void deleteInterruptedBeforeTheWriteChangesNothingAndTheRetrySucceeds() throws Exception {
        run(new Scenario("delete_messages", false, "UID FETCH", ERROR, 1, 0, "ok", 0, 1));
    }

    @Test
    void deleteWithLostAnswerRetriesToAlreadyGone() throws Exception {
        run(new Scenario("delete_messages", false, "UID MOVE", "unknown", 0, 1, "already_gone", 0, 1));
    }

    @Test
    void deleteInterruptedAfterTheAnswerRetriesToAlreadyGone() throws Exception {
        run(new Scenario("delete_messages", false, "UNSELECT", "ok", 0, 1, "already_gone", 0, 1));
    }

    @Test
    void fallbackDeleteWithLostCopyAnswerRetriesToOkWithADuplicateInTrash() throws Exception {
        run(new Scenario("delete_messages", true, "UID COPY", "unknown", 1, 1, "ok", 0, 2));
    }

    @Test
    void fallbackDeleteWithLostExpungeAnswerRetriesToAlreadyGone() throws Exception {
        run(new Scenario("delete_messages", true, "UID EXPUNGE", "unknown", 0, 1, "already_gone", 0, 1));
    }

    // ---- copy (not idempotent: a repeat duplicates) ----

    @Test
    void copyInterruptedBeforeTheWriteChangesNothingAndTheRetryCopiesOnce() throws Exception {
        run(new Scenario("copy_messages", false, "UID FETCH", ERROR, 1, 0, "ok", 1, 1));
    }

    @Test
    void copyWithLostAnswerIsUnknownAndTheRetryCreatesADuplicate() throws Exception {
        run(new Scenario("copy_messages", false, "UID COPY", "unknown", 1, 1, "ok", 1, 2));
    }

    @Test
    void copyInterruptedAfterTheAnswerIsOkAndTheRetryCreatesADuplicate() throws Exception {
        run(new Scenario("copy_messages", false, "UNSELECT", "ok", 1, 1, "ok", 1, 2));
    }

    // ---- set_flags (idempotent) ----

    @Test
    void setFlagsInterruptedBeforeTheWriteChangesNothingAndTheRetrySucceeds() throws Exception {
        run(new Scenario("set_flags", false, "UID FETCH", ERROR, 1, 0, "ok", 1, 0));
    }

    @Test
    void setFlagsWithLostAnswerIsUnknownAndTheRetryIsIdempotent() throws Exception {
        run(new Scenario("set_flags", false, "UID STORE", "unknown", 1, 0, "ok", 1, 0));
    }

    @Test
    void setFlagsInterruptedAfterTheAnswerIsOkAndTheRetryIsIdempotent() throws Exception {
        run(new Scenario("set_flags", false, "UNSELECT", "ok", 1, 0, "ok", 1, 0));
    }
}
