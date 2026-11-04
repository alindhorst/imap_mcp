package de.alexanderlindhorst.imapmcp.it;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * v2 QA hardening (#207, SPEC 5.8): the wire audit over every write tool on GreenMail and the three Dovecot
 * capability profiles, and pool reuse after a SELECT. The tools run in-process ({@link WriteHarness}); the evidence is
 * the client commands of the opt-in IMAP trace ({@link TraceTap}, rules in {@link ImapWireAudit}, themselves unit
 * tested). Containers are shared by all tests of the class; every test uses its own user (own mailbox).
 *
 * <p>Already covered elsewhere and not repeated here: per-tool UID-only/PEEK/close evidence on Dovecot in
 * {@code SetFlags/Copy/Move/Delete/CreateFolderDovecotIT}, tenant isolation in {@code TenantIsolationIT}.
 */
class WriteHardeningIT {

    private static final AtomicInteger USERS = new AtomicInteger();
    private static final String SRC = "WHSrc";
    private static final String TARGET_A = "WHTargetA";
    private static final String TARGET_B = "WHTargetB";

    private static GreenMailFixture greenMail;
    private static DovecotContainer full;
    private static DovecotContainer noMove;
    private static DovecotContainer noMoveNoUidplus;
    private static DovecotContainer noUtf8Accept;

    @TempDir
    Path dir;

    private TraceTap tap;
    private WriteHarness harness;

    @BeforeAll
    static void start() {
        greenMail = GreenMailFixture.start();
        full = new DovecotContainer(DovecotContainer.Profile.FULL);
        noMove = new DovecotContainer(DovecotContainer.Profile.NO_MOVE);
        noMoveNoUidplus = new DovecotContainer(DovecotContainer.Profile.NO_MOVE_NO_UIDPLUS);
        noUtf8Accept = new DovecotContainer(DovecotContainer.Profile.NO_UTF8_ACCEPT);
        full.start();
        noMove.start();
        noMoveNoUidplus.start();
        noUtf8Accept.start();
    }

    @AfterAll
    static void stop() {
        noUtf8Accept.stop();
        noMoveNoUidplus.stop();
        noMove.stop();
        full.stop();
        greenMail.close();
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

    // ---- backends ----

    /** A mailbox seeded with three messages in INBOX and three in {@link #SRC}, and the target folders. */
    private WriteHarness open(Backend backend) throws Exception {
        String user = "whardening" + USERS.incrementAndGet();
        try (MailSeeder seeder = backend.seeder(user)) {
            for (int i = 0; i < 3; i++) {
                seeder.appendPlain("INBOX", "hardening inbox " + i, "body");
                seeder.appendPlain(SRC, "hardening src " + i, "body");
            }
            seeder.appendPlain(TARGET_A, "hardening target seed", "body");
            seeder.appendPlain(TARGET_B, "hardening target seed", "body");
        }
        tap = new TraceTap().attach();
        harness = new WriteHarness(dir, backend.host(), backend.port(), user, backend.password());
        return harness;
    }

    private interface Backend {
        MailSeeder seeder(String user) throws Exception;

        String host();

        int port();

        String password();
    }

    private static Backend greenMailBackend() {
        return new Backend() {
            private static final String PASSWORD = "pw";

            @Override
            public MailSeeder seeder(String user) throws Exception {
                greenMail.createUser(user + "@localhost", user, PASSWORD);
                return greenMail.seeder(user, PASSWORD);
            }

            @Override
            public String host() {
                return GreenMailFixture.HOST;
            }

            @Override
            public int port() {
                return greenMail.imapsPort();
            }

            @Override
            public String password() {
                return PASSWORD;
            }
        };
    }

    private static Backend dovecotBackend(DovecotContainer c) {
        return new Backend() {
            @Override
            public MailSeeder seeder(String user) throws Exception {
                return c.seeder(user);
            }

            @Override
            public String host() {
                return c.getHost();
            }

            @Override
            public int port() {
                return c.imapsPort();
            }

            @Override
            public String password() {
                return DovecotContainer.PASSWORD;
            }
        };
    }

    // ---- wire audit ----

    /** One slice of the trace per write call, for the "target never selected" rule. */
    private record WriteSlice(String tool, String target, List<String> commands) {
    }

    /** Every write tool once; returns the slices of the calls that name a target folder. */
    private List<WriteSlice> runEveryWriteTool(WriteHarness h) {
        List<WriteSlice> slices = new ArrayList<>();
        assertThat(h.call("list_accounts", Map.of()).isError()).isFalse();
        List<String> inbox = h.handles("INBOX");
        List<String> src = h.handles(SRC);
        assertThat(inbox).hasSize(3);
        assertThat(src).hasSize(3);
        assertThat(h.callOn("get_message", "message", inbox.get(0)).isError()).isFalse();

        WriteHarness.Result flags = h.callOn("set_flags", "handles", List.of(inbox.get(0)), "seen", true,
                "flagged", true);
        assertThat(flags.isError()).isFalse();
        assertThat(flags.statuses()).containsExactly("ok");
        h.callOn("set_flags", "handles", List.of(inbox.get(1)), "add_keywords", List.of("whaudit")); // outcome varies by server
        WriteHarness.Result created = h.callOn("create_folder", "name", "WHNew");
        assertThat(created.isError()).as("%s", created.structured()).isFalse();
        assertThat(created.structured()).containsEntry("created", true);

        int m = tap.mark();
        WriteHarness.Result copy = h.callOn("copy_messages", "handles", List.of(inbox.get(1)), "target_folder",
                TARGET_A);
        slices.add(new WriteSlice("copy_messages", TARGET_A, tap.since(m)));
        m = tap.mark();
        WriteHarness.Result move = h.callOn("move_messages", "handles", List.of(inbox.get(2), src.get(0)),
                "target_folder", TARGET_B);
        slices.add(new WriteSlice("move_messages", TARGET_B, tap.since(m)));
        m = tap.mark();
        WriteHarness.Result delete = h.callOn("delete_messages", "handles", List.of(src.get(1)));
        slices.add(new WriteSlice("delete_messages", "Trash", tap.since(m)));

        if (h.hasCapability("uidplus")) {
            assertThat(copy.isError()).as("%s", copy.structured()).isFalse();
            assertThat(copy.statuses()).containsExactly("ok");
            assertThat(move.isError()).as("%s", move.structured()).isFalse();
            assertThat(move.statuses()).containsExactly("ok", "ok");
        } else {
            assertThat(copy.code()).isEqualTo("UNSUPPORTED");
            assertThat(move.code()).isEqualTo("UNSUPPORTED");
            assertThat(delete.code()).isEqualTo("UNSUPPORTED");
        }
        return slices;
    }

    private void auditWholeTrace(boolean strictEnable) {
        List<String> all = tap.all();
        assertThat(all).as("the trace was recorded").isNotEmpty();
        assertThat(ImapWireAudit.violations(all)).isEmpty();
        assertThat(ImapWireAudit.enableViolations(all, strictEnable)).isEmpty();
        assertThat(all).anyMatch(c -> c.contains("BODY.PEEK["));
    }

    private void auditTargets(List<WriteSlice> slices) {
        for (WriteSlice s : slices) {
            assertThat(ImapWireAudit.selectedFolders(s.commands(), List.of(s.target())))
                    .as("%s must not select its target", s.tool()).isEmpty();
        }
    }

    @Test
    void wireAuditGreenMail() throws Exception {
        WriteHarness h = open(greenMailBackend());
        List<WriteSlice> slices = runEveryWriteTool(h);
        auditWholeTrace(true); // GreenMail does not advertise UTF8=ACCEPT: Angus sends no ENABLE at all
        auditTargets(slices);
    }

    @Test
    void wireAuditDovecotFull() throws Exception {
        WriteHarness h = open(dovecotBackend(full));
        List<WriteSlice> slices = runEveryWriteTool(h);
        assertThat(tap.all()).anyMatch(c -> c.startsWith("UID MOVE"));
        auditWholeTrace(false);
        assertTheOnlyEnableIsUtf8AcceptDuringLogin(tap.all());
        auditTargets(slices);
    }

    @Test
    void wireAuditDovecotNoMove() throws Exception {
        WriteHarness h = open(dovecotBackend(noMove));
        List<WriteSlice> slices = runEveryWriteTool(h);
        assertThat(tap.all()).noneMatch(c -> c.startsWith("UID MOVE")).anyMatch(c -> c.startsWith("UID EXPUNGE"));
        auditWholeTrace(false);
        auditTargets(slices);
    }

    @Test
    void wireAuditDovecotNoMoveNoUidplus() throws Exception {
        WriteHarness h = open(dovecotBackend(noMoveNoUidplus));
        List<WriteSlice> slices = runEveryWriteTool(h);
        assertThat(tap.all()).noneMatch(c -> c.startsWith("UID EXPUNGE") || c.startsWith("UID COPY")
                || c.startsWith("UID MOVE"));
        auditWholeTrace(false);
        auditTargets(slices);
    }

    /**
     * SPEC 5.4 (v0.9): the client code never sends ENABLE; on a server that advertises UTF8=ACCEPT Angus 2.0.5 does so
     * itself. Against the profile without UTF8=ACCEPT the strict rule therefore holds: no ENABLE at all (#179).
     */
    @Test
    void wireAuditDovecotWithoutUtf8AcceptHasNoEnableAtAll() throws Exception {
        WriteHarness h = open(dovecotBackend(noUtf8Accept));
        List<WriteSlice> slices = runEveryWriteTool(h);
        assertThat(tap.all()).anyMatch(c -> c.startsWith("UID MOVE"));
        assertThat(ImapWireAudit.enableViolations(tap.all(), true)).isEmpty();
        auditWholeTrace(true);
        auditTargets(slices);
    }

    /**
     * On the full set the relaxed rule is exact: the only ENABLE is {@code UTF8=ACCEPT} (never CONDSTORE/QRESYNC), and
     * it is sent during login, before any folder command.
     */
    private static void assertTheOnlyEnableIsUtf8AcceptDuringLogin(List<String> all) {
        List<String> enables = all.stream().filter(c -> c.startsWith("ENABLE")).toList();
        assertThat(enables).isNotEmpty().allMatch(c -> c.strip().equals("ENABLE UTF8=ACCEPT"));
        int firstEnable = all.indexOf(enables.get(0));
        int firstFolderCommand = all.indexOf(ImapWireAudit.firstFolderCommand(all).orElseThrow());
        // The login itself is not a traced command: the connection's first traced command is the ENABLE.
        assertThat(firstEnable).isZero();
        assertThat(firstEnable).isLessThan(firstFolderCommand);
    }

    // ---- pool reuse after SELECT ----

    private void assertReadAfterCleanWriteReusesTheStore(Backend backend) throws Exception {
        WriteHarness h = open(backend);
        List<String> inbox = h.handles("INBOX");
        WriteHarness.Result write = h.callOn("set_flags", "handles", List.of(inbox.get(0)), "seen", true);
        assertThat(write.statuses()).containsExactly("ok");
        List<String> writeWire = tap.all();
        long created = h.pool.createdCount();
        long destroyed = h.pool.destroyedCount();
        assertThat(h.pool.numActive(h.key)).isZero();

        int m = tap.mark();
        assertThat(h.callOn("search_messages", "folder", "INBOX").isError()).isFalse();
        List<String> readWire = tap.since(m);

        assertThat(h.pool.createdCount()).as("the same Store served the read call").isEqualTo(created);
        assertThat(h.pool.destroyedCount()).isEqualTo(destroyed);
        assertThat(readWire).as("no new connection: no LOGIN").noneMatch(c -> c.startsWith("LOGIN")
                || c.startsWith("AUTHENTICATE"));
        assertThat(ImapWireAudit.firstFolderCommand(readWire)).get().asString().startsWith("EXAMINE");
        assertThat(readWire).noneMatch(c -> c.startsWith("SELECT"));
        // The write call left nothing selected: its last folder command is the close (UNSELECT or EXAMINE + CLOSE).
        List<String> tail = writeWire.subList(Math.max(0, writeWire.size() - 3), writeWire.size());
        assertThat(tail).anyMatch(c -> c.equals("UNSELECT") || c.equals("CLOSE"));
        assertThat(ImapWireAudit.violations(tap.all())).isEmpty();
    }

    @Test
    void readAfterCleanWriteReusesTheStoreOnGreenMail() throws Exception {
        assertReadAfterCleanWriteReusesTheStore(greenMailBackend());
    }

    @Test
    void readAfterCleanWriteReusesTheStoreOnDovecot() throws Exception {
        assertReadAfterCleanWriteReusesTheStore(dovecotBackend(full));
    }
}
