package de.alexanderlindhorst.imapmcp.it;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import de.alexanderlindhorst.imapmcp.app.AppComponents;
import de.alexanderlindhorst.imapmcp.app.AppComponentsAccess;
import de.alexanderlindhorst.imapmcp.app.config.AppConfig;
import de.alexanderlindhorst.imapmcp.app.config.AppPaths;
import de.alexanderlindhorst.imapmcp.app.config.ConfigLoader;
import de.alexanderlindhorst.imapmcp.core.account.PoolKey;
import de.alexanderlindhorst.imapmcp.core.account.ResolvedAccount;
import de.alexanderlindhorst.imapmcp.core.imap.pool.StorePool;
import eu.rekawek.toxiproxy.model.ToxicDirection;
import io.modelcontextprotocol.common.McpTransportContext;
import io.modelcontextprotocol.server.McpServerFeatures;
import io.modelcontextprotocol.server.McpSyncServerExchange;
import io.modelcontextprotocol.spec.McpSchema;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.testcontainers.containers.Network;

/**
 * Interrupted v3a tools (SPEC 5.5, 5.8) against Dovecot through Toxiproxy, the real tools in-process with a mocked
 * elicitation exchange that accepts. The faults start exactly between two IMAP commands ({@link TraceScript}).
 *
 * <p>{@code purge_messages} (deadline 60 s, the minimum while it is registered): the answer to the UID EXPUNGE, and
 * the answer to the post-check, miss the deadline. Both are {@code TIMEOUT} with outcome {@code unknown}; the server
 * has executed the commands that reached it, so a retry reports {@code ok} or {@code already_gone} and nothing else is
 * touched. {@code download_attachment}: a connection reset and a late block in the middle of a part leave no
 * temporary file and no final name behind.
 */
class V3aInterruptionIT {

    private static final AtomicInteger USERS = new AtomicInteger();
    private static final Duration PURGE_DEADLINE = Duration.ofSeconds(60);

    private static Network network;
    private static DovecotContainer dovecot;
    private static ToxiproxyFixture toxiproxy;
    /** One proxy per test: Toxiproxy cannot remove a latency toxic while a delayed connection is stuck (503). */
    private ToxiproxyFixture.Proxy proxy;

    @TempDir
    Path dir;

    private final List<AppComponents> created = new ArrayList<>();
    private TraceScript script;

    @BeforeAll
    static void start() throws Exception {
        network = Network.newNetwork();
        dovecot = new DovecotContainer().withNetwork(network);
        toxiproxy = new ToxiproxyFixture(network);
        dovecot.start();
        toxiproxy.start();
    }

    @org.junit.jupiter.api.BeforeEach
    void newProxy() throws Exception {
        proxy = toxiproxy.proxyTo("imap-v3a-fault-" + USERS.incrementAndGet(), DovecotContainer.NETWORK_ALIAS,
                DovecotContainer.IMAPS_PORT);
    }

    @AfterAll
    static void stop() {
        toxiproxy.stop();
        dovecot.stop();
        network.close();
    }

    @AfterEach
    void cleanUp() throws Exception {
        if (script != null) {
            script.detach();
        }
        try {
            clearToxics();
        } catch (Exception e) {
            // a stuck delayed stream makes Toxiproxy's control API fail; the proxy belongs to this test only
        }
        created.forEach(AppComponents::close);
    }

    private void clearToxics() throws Exception {
        if (!proxy.toxics().toxics().getAll().isEmpty()) {
            proxy.disable(); // drops the connections a delayed stream holds; removing the toxic otherwise answers 503
        }
        for (var t : proxy.toxics().toxics().getAll()) {
            for (int attempt = 1;; attempt++) { // Toxiproxy answers 503 while it still flushes a delayed stream
                try {
                    t.remove();
                    break;
                } catch (java.io.IOException e) {
                    if (attempt == 3) {
                        break; // a stuck delayed stream; this test's proxy is not used again
                    }
                    Thread.sleep(1000);
                }
            }
        }
        proxy.enable();
    }

    /** Delays every answer from now on; installed while the trigger command is traced, before it is sent. */
    private TraceScript.Action delayAnswers(int ms) {
        return () -> proxy.toxics().toxics().latency("slow", ToxicDirection.DOWNSTREAM, ms);
    }

    private TraceScript.Action resetNow() {
        return () -> proxy.toxics().toxics().resetPeer("reset", ToxicDirection.DOWNSTREAM, 0);
    }

    private static void eventually(String what, BooleanSupplier condition) throws InterruptedException {
        long end = System.nanoTime() + Duration.ofSeconds(15).toNanos();
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() > end) {
                throw new AssertionError("timed out waiting for: " + what);
            }
            Thread.sleep(50);
        }
    }

    // ---- fixture ----

    private record Result(McpSchema.CallToolResult raw, Duration took) {
        Map<String, Object> structured() {
            return ToolResults.payload(raw);
        }

        boolean isError() {
            return Boolean.TRUE.equals(raw.isError());
        }

        @SuppressWarnings("unchecked")
        Map<String, Object> error() {
            return (Map<String, Object>) structured().get("error");
        }

        @SuppressWarnings("unchecked")
        Map<String, Object> details() {
            return (Map<String, Object>) error().get("details");
        }

        @SuppressWarnings("unchecked")
        List<String> statuses() {
            return ((List<Map<String, Object>>) structured().get("results")).stream()
                    .map(i -> (String) i.get("status")).toList();
        }
    }

    /** One account against the proxy; the user accepts every elicitation request. */
    private final class Fixture {
        final String user = "v3a" + USERS.incrementAndGet();
        final AppComponents components;
        final StorePool pool;
        final String account;
        final Path downloads;
        private final Map<String, McpServerFeatures.SyncToolSpecification> specs = new HashMap<>();
        private final McpSyncServerExchange exchange = mock(McpSyncServerExchange.class);

        Fixture(Duration deadline, boolean purge) throws Exception {
            downloads = Files.createDirectory(dir.resolve("downloads-" + user));
            Files.setPosixFilePermissions(downloads, PosixFilePermissions.fromString("rwx------"));
            Path config = dir.resolve(user + ".yaml");
            Files.writeString(config, """
                    accounts:
                      - id: work
                        host: %s
                        port: %d
                        username: %s
                        password: "env:IT_PW"
                    %s
                    imap:
                      readTimeout: 120s
                      trace: true
                    tools:
                      deadline: %ds
                      download:
                        dir: %s
                    """.formatted(proxy.host(), proxy.port(), user, purge ? "    allowPurge: true" : "",
                    deadline.toSeconds(), downloads));
            AppConfig cfg = ConfigLoader.load(config, Map.of());
            AppPaths paths = new AppPaths(config, dir.resolve("state-" + user), dir.resolve("logs-" + user));
            components = AppComponentsAccess.create(cfg, paths, Map.of("IT_PW", DovecotContainer.PASSWORD), p -> {
                p.put("mail.imaps.ssl.trust", "*");
                p.put("mail.imaps.ssl.checkserveridentity", "false");
                return p;
            });
            created.add(components);
            pool = AppComponentsAccess.pool(components);
            components.statefulToolSpecs().forEach(s -> specs.put(s.tool().name(), s));
            when(exchange.transportContext()).thenReturn(McpTransportContext.EMPTY);
            when(exchange.getClientCapabilities())
                    .thenReturn(McpSchema.ClientCapabilities.builder().elicitation(true, false).build());
            when(exchange.createElicitation(any()))
                    .thenReturn(new McpSchema.ElicitResult(McpSchema.ElicitResult.Action.ACCEPT, Map.of()));
            account = account();
        }

        @SuppressWarnings("unchecked")
        private String account() {
            Map<String, Object> out = call("list_accounts", Map.of()).structured();
            return (String) ((List<Map<String, Object>>) out.get("accounts")).get(0).get("account");
        }

        Result call(String tool, Map<String, Object> args) {
            long t0 = System.nanoTime();
            McpSchema.CallToolResult r = specs.get(tool).callHandler().apply(exchange,
                    new McpSchema.CallToolRequest(tool, args));
            return new Result(r, Duration.ofNanos(System.nanoTime() - t0));
        }

        @SuppressWarnings("unchecked")
        String trashFolder() {
            Map<String, Object> out = call("list_folders", Map.of("account", account)).structured();
            return ((List<Map<String, Object>>) out.get("folders")).stream()
                    .filter(f -> "trash".equals(f.get("role"))).map(f -> (String) f.get("folder")).findFirst()
                    .orElseThrow();
        }

        @SuppressWarnings("unchecked")
        Map<String, String> handlesBySubject(String folder) {
            Map<String, Object> out = call("search_messages", Map.of("account", account, "folder", folder,
                    "page_size", 100)).structured();
            Map<String, String> handles = new java.util.LinkedHashMap<>();
            for (Map<String, Object> m : (List<Map<String, Object>>) out.get("messages")) {
                handles.put((String) ((Map<String, Object>) m.get("untrusted")).get("subject"),
                        (String) m.get("handle"));
            }
            return handles;
        }

        Result purge(String... handles) {
            return call("purge_messages", Map.of("account", account, "handles", List.of(handles)));
        }

        MailboxSnapshot snapshot(String... folders) throws Exception {
            try (MailSeeder seeder = dovecot.seeder(user)) {
                return MailboxSnapshot.of(seeder.store(), folders);
            }
        }

        List<String> filesInDownloads() throws Exception {
            try (Stream<Path> s = Files.list(downloads)) {
                return s.map(p -> p.getFileName().toString()).sorted().toList();
            }
        }
    }

    /** Seeds Trash with p1, p2 and keep (the message the purge must not touch); returns the UIDs. */
    private long[] seedTrash(Fixture f, String trash) throws Exception {
        try (MailSeeder s = dovecot.seeder(f.user)) {
            return new long[] {s.appendPlain(trash, "p1", "body"), s.appendPlain(trash, "p2", "body"),
                    s.appendPlain(trash, "keep", "body")};
        }
    }

    private static void assertStoredOrGone(MailboxSnapshot snapshot, String trash, long uid) {
        var flags = snapshot.folder(trash).flags().get(uid);
        assertThat(flags == null || flags.contains("\\Deleted"))
                .as("UID %d is gone or flagged \\Deleted, the STORE had been answered", uid).isTrue();
    }

    // ---- purge ----

    /** Interruption after the STORE: the UID EXPUNGE reaches the server, its answer misses the deadline. */
    @Test
    void purgeWithTheUidExpungeAnswerMissingTheDeadlineIsUnknownAndTheRetryFindsItDone() throws Exception {
        Fixture f = new Fixture(PURGE_DEADLINE, true);
        String trash = f.trashFolder();
        long[] uids = seedTrash(f, trash);
        Map<String, String> handles = f.handlesBySubject(trash);
        MailboxSnapshot before = f.snapshot(trash);
        long destroyedBefore = f.pool.destroyedCount();
        script = new TraceScript().attach().on("UID EXPUNGE", delayAnswers(75_000));

        Result r = f.purge(handles.get("p1"), handles.get("p2"));

        assertThat(script.done()).as("the fault was injected after the STORE: %s", r.structured()).isTrue();
        assertThat(r.isError()).isTrue();
        assertThat(r.error().get("code")).isEqualTo("TIMEOUT");
        assertThat(r.details()).containsEntry("outcome", "unknown");
        assertThat(r.took()).isBetween(Duration.ofSeconds(59), Duration.ofSeconds(68));
        eventually("store discarded on timeout", () -> f.pool.destroyedCount() > destroyedBefore);
        clearToxics();
        MailboxSnapshot after = f.snapshot(trash);
        assertStoredOrGone(after, trash, uids[0]);
        assertStoredOrGone(after, trash, uids[1]);
        assertThat(after.folder(trash).flags().get(uids[2])).as("the other message is untouched")
                .isEqualTo(before.folder(trash).flags().get(uids[2]));

        Result retry = f.purge(handles.get("p1"), handles.get("p2"));

        assertThat(retry.isError()).as("%s", retry.raw()).isFalse();
        assertThat(retry.statuses()).allMatch(s -> s.equals("ok") || s.equals("already_gone"));
        MailboxSnapshot end = f.snapshot(trash);
        assertThat(end.folder(trash).flags()).containsOnlyKeys(uids[2]);
        assertThat(end.folder(trash).flags().get(uids[2])).isEqualTo(before.folder(trash).flags().get(uids[2]));
    }

    /** Interruption after the UID EXPUNGE: the post-check answer misses the deadline; the messages are gone. */
    @Test
    void purgeWithThePostCheckAnswerMissingTheDeadlineIsUnknownAndTheRetryReportsAlreadyGone() throws Exception {
        Fixture f = new Fixture(PURGE_DEADLINE, true);
        String trash = f.trashFolder();
        long[] uids = seedTrash(f, trash);
        Map<String, String> handles = f.handlesBySubject(trash);
        MailboxSnapshot before = f.snapshot(trash);
        long destroyedBefore = f.pool.destroyedCount();
        script = new TraceScript().attach()
                .on("UID FETCH", () -> { }) // the existence pre-check
                .on("UID FETCH", delayAnswers(75_000)); // the post-check

        Result r = f.purge(handles.get("p1"), handles.get("p2"));

        assertThat(script.done()).as("the fault was injected after the UID EXPUNGE: %s", r.structured()).isTrue();
        assertThat(r.isError()).isTrue();
        assertThat(r.error().get("code")).isEqualTo("TIMEOUT");
        assertThat(r.details()).containsEntry("outcome", "unknown");
        assertThat(r.took()).isBetween(Duration.ofSeconds(59), Duration.ofSeconds(68));
        eventually("store discarded on timeout", () -> f.pool.destroyedCount() > destroyedBefore);
        clearToxics();
        MailboxSnapshot after = f.snapshot(trash);
        MailboxSnapshot.Diff diff = MailboxSnapshot.diff(before, after);
        assertThat(diff.removed(trash)).containsExactlyInAnyOrder(uids[0], uids[1]);
        assertThat(diff.flagChanged(trash)).isEmpty();

        Result retry = f.purge(handles.get("p1"), handles.get("p2"));

        assertThat(retry.isError()).isFalse();
        assertThat(retry.statuses()).containsExactly("already_gone", "already_gone");
        assertThat(MailboxSnapshot.diff(after, f.snapshot(trash)).isEmpty()).isTrue();
    }

    /** A reset at the UID EXPUNGE: whichever side of the command the server was, a retry ends in the same state. */
    @Test
    void purgeWithAConnectionResetAtTheUidExpungeIsConvergedByTheRetry() throws Exception {
        Fixture f = new Fixture(PURGE_DEADLINE, true);
        String trash = f.trashFolder();
        long[] uids = seedTrash(f, trash);
        Map<String, String> handles = f.handlesBySubject(trash);
        MailboxSnapshot before = f.snapshot(trash);
        script = new TraceScript().attach().on("UID EXPUNGE", resetNow());

        Result r = f.purge(handles.get("p1"), handles.get("p2"));

        assertThat(script.done()).isTrue();
        assertThat(r.took()).as("a result or a mapped error, no hang").isLessThan(Duration.ofSeconds(70));
        clearToxics();
        MailboxSnapshot after = f.snapshot(trash);
        assertStoredOrGone(after, trash, uids[0]);
        assertStoredOrGone(after, trash, uids[1]);

        Result retry = f.purge(handles.get("p1"), handles.get("p2"));

        assertThat(retry.isError()).as("%s", retry.raw()).isFalse();
        assertThat(retry.statuses()).allMatch(s -> s.equals("ok") || s.equals("already_gone"));
        assertThat(f.snapshot(trash).folder(trash).flags()).containsOnlyKeys(uids[2]);
        assertThat(f.snapshot(trash).folder(trash).flags().get(uids[2]))
                .isEqualTo(before.folder(trash).flags().get(uids[2]));
    }

    // ---- download ----

    private static byte[] mixedWithBase64(String subject, byte[] data) {
        String b64 = new String(Base64.getMimeEncoder(76, new byte[] {'\r', '\n'}).encode(data),
                StandardCharsets.ISO_8859_1);
        String raw = "From: sender@example.org\r\nTo: recipient@example.org\r\nSubject: " + subject
                + "\r\nDate: Wed, 04 Nov 2026 10:00:00 +0000\r\nMIME-Version: 1.0\r\n"
                + "Content-Type: multipart/mixed; boundary=\"bnd\"\r\n\r\n"
                + "--bnd\r\nContent-Type: text/plain; charset=us-ascii\r\n\r\nsee attached\r\n"
                + "--bnd\r\nContent-Type: application/octet-stream; name=\"part.bin\"\r\n"
                + "Content-Transfer-Encoding: base64\r\nContent-Disposition: attachment; filename=\"part.bin\"\r\n\r\n"
                + b64 + "\r\n--bnd--\r\n";
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.writeBytes(raw.getBytes(StandardCharsets.ISO_8859_1));
        return out.toByteArray();
    }

    private String seedDownloadMessage(Fixture f) throws Exception {
        byte[] data = new byte[3_000_000]; // about 4 MB of base64 text: four 1 MiB blocks
        new Random(11).nextBytes(data);
        try (MailSeeder s = dovecot.seeder(f.user)) {
            s.appendRfc822("INBOX", mixedWithBase64("dl-interrupt", data));
        }
        return f.handlesBySubject("INBOX").get("dl-interrupt");
    }

    private static boolean secondBlock(String line) {
        return line.matches("[A-Z]+\\d+ .*BODY\\.PEEK\\[2\\]<1048576\\.1048576>.*");
    }

    @Test
    void aConnectionResetMidPartLeavesNoTemporaryFileAndNoFinalName() throws Exception {
        Fixture f = new Fixture(Duration.ofSeconds(30), false);
        String handle = seedDownloadMessage(f);
        long destroyedBefore = f.pool.destroyedCount();
        script = new TraceScript().attach().onLine(V3aInterruptionIT::secondBlock, resetNow());

        Result r = f.call("download_attachment", Map.of("account", f.account, "message", handle, "part", "2"));

        assertThat(script.done()).as("the reset hit the second block: %s", r.structured()).isTrue();
        assertThat(r.isError()).isTrue();
        assertThat(r.error().get("code")).isIn("UPSTREAM_UNAVAILABLE", "UPSTREAM_ERROR", "TIMEOUT");
        eventually("broken store discarded", () -> f.pool.destroyedCount() > destroyedBefore);
        assertThat(f.filesInDownloads()).isEmpty();
    }

    @Test
    void aBlockMissingTheDeadlineIsAPlainTimeoutAndTheWorkersCleanupLeavesNoFile() throws Exception {
        Fixture f = new Fixture(Duration.ofSeconds(30), false);
        String handle = seedDownloadMessage(f);
        long destroyedBefore = f.pool.destroyedCount();
        script = new TraceScript().attach().onLine(V3aInterruptionIT::secondBlock, delayAnswers(36_000));

        Result r = f.call("download_attachment", Map.of("account", f.account, "message", handle, "part", "2"));

        assertThat(script.done()).as("the delay hit the second block: %s", r.structured()).isTrue();
        assertThat(r.isError()).isTrue();
        assertThat(r.error().get("code")).as("nothing was published: a plain timeout").isEqualTo("TIMEOUT");
        assertThat(r.took()).isBetween(Duration.ofSeconds(29), Duration.ofSeconds(36));
        eventually("store discarded on timeout", () -> f.pool.destroyedCount() > destroyedBefore);
        eventually("the abandoned worker removed its temporary file", () -> {
            try {
                return f.filesInDownloads().isEmpty();
            } catch (Exception e) {
                return false;
            }
        });
        assertThat(f.filesInDownloads()).as("no temporary file, no final name").isEmpty();
    }
}
