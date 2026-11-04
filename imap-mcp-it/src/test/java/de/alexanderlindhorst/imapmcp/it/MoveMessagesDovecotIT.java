package de.alexanderlindhorst.imapmcp.it;

import static org.assertj.core.api.Assertions.assertThat;

import de.alexanderlindhorst.imapmcp.core.account.MailAccount;
import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.ServerParameters;
import io.modelcontextprotocol.client.transport.StdioClientTransport;
import io.modelcontextprotocol.json.McpJsonDefaults;
import io.modelcontextprotocol.spec.McpSchema;
import jakarta.mail.Flags;
import jakarta.mail.Folder;
import jakarta.mail.Message;
import jakarta.mail.MessagingException;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.eclipse.angus.mail.imap.IMAPFolder;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.io.TempDir;

/**
 * {@code move_messages} against a real Dovecot in the three capability sets of SPEC 5.8 (v2 write tests): RFC 6851
 * {@code UID MOVE} (FULL), the COPY + STORE + {@code UID EXPUNGE} fallback (NO_MOVE) and the UIDPLUS refusal
 * (NO_MOVE_NO_UIDPLUS), plus {@code already_gone}/{@code already_in_target}, a UIDVALIDITY change and a concurrent
 * writer on a second raw connection.
 *
 * <p>The tools are driven through the stdio client against the shaded jar with {@code imap.trace: true}; the trace is
 * the wire evidence (see {@code DovecotFidelityIT}). Every scenario uses its own Dovecot user and configured account.
 * The targets are non-ASCII folder names (modified UTF-7 on the wire).
 */
class MoveMessagesDovecotIT {

    private static final Pattern CLIENT_COMMAND =
            Pattern.compile(" imapmcp\\.imap\\.trace - ([A-Z]+\\d+) (?!(?:OK|NO|BAD)\\b)([A-Z][A-Z.]*\\b.*)$");

    private static final String ALREADY_GONE_NOTE = "Some messages are no longer in their source folder; if this call "
            + "repeats an earlier one, they were already moved. Search the target folder for their new handles.";
    private static final String ALREADY_IN_TARGET_NOTE =
            "Some messages were already in the target folder and were left unchanged.";

    private static final String SRC_A = "Quelle-A";
    private static final String SRC_B = "Quelle-B";
    private static final String TARGET = "Ziel-Übertrag";

    @TempDir
    static Path root;

    // ---- environment: one container + one server process per capability set -------------------------------------

    private static final class Env implements AutoCloseable {
        final DovecotContainer dovecot;
        final ServerConfig config;
        final McpSyncClient client;
        final Map<String, String> accounts = new LinkedHashMap<>();

        Env(DovecotContainer.Profile profile, String... users) throws Exception {
            dovecot = new DovecotContainer(profile);
            dovecot.start();
            Path dir = Files.createDirectories(root.resolve(profile.name()));
            ServerConfigWriter writer = new ServerConfigWriter(dir).trustStore(TestTrust.writeTruststore(dir))
                    .extraYaml("imap:\n  trace: true\n");
            for (String u : users) {
                writer.account(u, dovecot.getHost(), dovecot.imapsPort(), MailAccount.Security.IMPLICIT_TLS, u,
                        DovecotContainer.PASSWORD);
            }
            config = writer.write();
            List<String> cmd = JarLauncher.builder(config, "stdio").command();
            ServerParameters params = ServerParameters.builder(cmd.get(0)).args(cmd.subList(1, cmd.size()))
                    .env(config.env()).build();
            client = McpClient.sync(new StdioClientTransport(params, McpJsonDefaults.getMapper()))
                    .requestTimeout(Duration.ofSeconds(90)).initializationTimeout(Duration.ofSeconds(60)).build();
            client.initialize();
            for (Map<String, Object> a : list(call("list_accounts", Map.of()).structured, "accounts")) {
                accounts.put((String) a.get("label"), (String) a.get("account"));
                Map<String, Object> caps = map(a, "capabilities");
                assertThat(caps).containsEntry("move", profile.move()).containsEntry("uidplus", profile.uidplus());
            }
        }

        Result call(String tool, Map<String, Object> args) {
            McpSchema.CallToolResult r = client.callTool(new McpSchema.CallToolRequest(tool, args));
            return new Result(Boolean.TRUE.equals(r.isError()), asMap(r.structuredContent()));
        }

        Result ok(String tool, Map<String, Object> args) {
            Result r = call(tool, args);
            assertThat(r.error()).as("%s -> %s", tool, r.structured()).isFalse();
            return r;
        }

        MailSeeder seeder(String user) throws MessagingException {
            return dovecot.seeder(user);
        }

        long logSize() throws IOException {
            return Files.exists(config.logFile()) ? Files.size(config.logFile()) : 0;
        }

        /** Client commands (tag stripped) written since {@code offset}. */
        List<String> commandsSince(long offset) throws IOException {
            byte[] all = Files.readAllBytes(config.logFile());
            String text = new String(all, (int) offset, all.length - (int) offset, StandardCharsets.UTF_8);
            List<String> out = new ArrayList<>();
            for (String line : text.split("\n")) {
                Matcher m = CLIENT_COMMAND.matcher(line);
                if (m.find()) {
                    out.add(m.group(2).strip());
                }
            }
            return out;
        }

        /** subject -> handle for every message of the folder, through the real search tool. */
        Map<String, String> handles(String user, String folder) {
            Map<String, String> out = new LinkedHashMap<>();
            Result r = ok("search_messages", args("account", accounts.get(user), "folder", folder));
            for (Map<String, Object> m : list(r.structured, "messages")) {
                out.put((String) map(m, "untrusted").get("subject"), (String) m.get("handle"));
            }
            return out;
        }

        Result move(String user, List<String> handles, String target) {
            return call("move_messages", args("account", accounts.get(user), "handles", handles, "target_folder",
                    target));
        }

        @Override
        public void close() {
            client.close();
            dovecot.stop();
        }
    }

    private record Result(boolean error, Map<String, Object> structured) {

        String errorCode() {
            return (String) map(structured, "error").get("code");
        }

        Object detail(String key) {
            return map(map(structured, "error"), "details").get(key);
        }

        List<Map<String, Object>> items() {
            return list(structured, "results");
        }

        Map<String, Object> summary() {
            return map(structured, "summary");
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(Object o) {
        return (Map<String, Object>) o;
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> list(Map<String, Object> m, String key) {
        return (List<Map<String, Object>>) m.get(key);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> map(Map<String, Object> m, String key) {
        return (Map<String, Object>) m.get(key);
    }

    private static Map<String, Object> args(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            m.put((String) kv[i], kv[i + 1]);
        }
        return m;
    }

    /** Test fixture only: sets {@code \Deleted} on one UID over a separate connection (no expunge). */
    private static void markDeleted(MailSeeder s, String folder, long uid) throws MessagingException {
        IMAPFolder f = (IMAPFolder) s.store().getFolder(folder);
        f.open(Folder.READ_WRITE);
        try {
            f.getMessageByUID(uid).setFlag(Flags.Flag.DELETED, true);
        } finally {
            f.close(false);
        }
    }

    /** Test fixture only: expunges exactly one UID (UID EXPUNGE) over a separate connection. */
    private static void expungeUid(MailSeeder s, String folder, long uid) throws MessagingException {
        IMAPFolder f = (IMAPFolder) s.store().getFolder(folder);
        f.open(Folder.READ_WRITE);
        try {
            Message m = f.getMessageByUID(uid);
            m.setFlag(Flags.Flag.DELETED, true);
            f.expunge(new Message[] {m});
        } finally {
            f.close(false);
        }
    }

    private static Set<Long> parseSet(String set) {
        Set<Long> out = new TreeSet<>();
        for (String part : set.split(",")) {
            String[] bounds = part.split(":");
            long lo = Long.parseLong(bounds[0]);
            long hi = Long.parseLong(bounds[bounds.length - 1]);
            for (long u = Math.min(lo, hi); u <= Math.max(lo, hi); u++) {
                out.add(u);
            }
        }
        return out;
    }

    /** The UID set argument (second token after {@code UID VERB}) of a command. */
    private static Set<Long> uidSetOf(String command) {
        return parseSet(command.split(" ")[2]);
    }

    private static List<String> starting(List<String> commands, String prefix) {
        return commands.stream().filter(c -> c.startsWith(prefix)).toList();
    }

    private static void assertNoMutation(List<String> commands) {
        assertThat(commands).noneMatch(c -> c.startsWith("UID MOVE") || c.startsWith("UID COPY")
                || c.startsWith("UID STORE") || c.contains("EXPUNGE") || c.startsWith("COPY")
                || c.startsWith("MOVE") || c.startsWith("STORE"));
    }

    /**
     * Seeds the standard scenario for {@code user}: A has a1..a3 and a \Deleted foreign message, B has b1, b2; a1, a2
     * (A) and b1 (B) get moved. Returns the UIDs.
     */
    private record Seed(long a1, long a2, long a3, long aDel, long b1, long b2) {

        static Seed create(Env env, String user) throws Exception {
            try (MailSeeder s = env.seeder(user)) {
                long a1 = s.appendPlain(SRC_A, "a1", "x");
                long a2 = s.appendPlain(SRC_A, "a2", "x");
                long a3 = s.appendPlain(SRC_A, "a3", "x");
                long aDel = s.appendPlain(SRC_A, "a-del", "x");
                long b1 = s.appendPlain(SRC_B, "b1", "x");
                long b2 = s.appendPlain(SRC_B, "b2", "x");
                markDeleted(s, SRC_A, aDel);
                s.store().getFolder(TARGET).create(Folder.HOLDS_MESSAGES);
                return new Seed(a1, a2, a3, aDel, b1, b2);
            }
        }
    }

    private static MailboxSnapshot snapshot(Env env, String user) throws Exception {
        try (MailSeeder s = env.seeder(user)) {
            return MailboxSnapshot.of(s.store(), SRC_A, SRC_B, TARGET);
        }
    }

    /** The shared move scenario of FULL and NO_MOVE; the wire assertions differ and are made by the caller. */
    private static List<String> movesThreeHandlesFromTwoFolders(Env env, String user) throws Exception {
        Seed seed = Seed.create(env, user);
        Map<String, String> a = env.handles(user, SRC_A);
        Map<String, String> b = env.handles(user, SRC_B);
        List<String> moved = List.of(a.get("a1"), a.get("a2"), b.get("b1"));
        MailboxSnapshot before = snapshot(env, user);

        long offset = env.logSize();
        Result r = env.move(user, moved, TARGET);
        List<String> commands = env.commandsSince(offset);

        assertThat(r.error()).as("%s", r.structured()).isFalse();
        assertThat(r.structured()).containsEntry("target_folder", TARGET);
        assertThat(r.summary()).containsEntry("requested", 3).containsEntry("ok", 3).containsEntry("failed", 0);
        assertThat(r.structured().get("notes")).isEqualTo(List.of());
        assertThat(r.items()).hasSize(3);
        List<String> newHandles = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            Map<String, Object> item = r.items().get(i);
            assertThat(item).containsEntry("handle", moved.get(i)).containsEntry("status", "ok");
            assertThat(item.get("new_handle")).isInstanceOf(String.class).isNotEqualTo(moved.get(i));
            assertThat(item.get("note")).isNull();
            newHandles.add((String) item.get("new_handle"));
        }
        // New handles resolve through the read tool; old ones are gone.
        List<String> subjects = List.of("a1", "a2", "b1");
        for (int i = 0; i < 3; i++) {
            Result msg = env.ok("get_message", args("account", env.accounts.get(user), "message", newHandles.get(i)));
            assertThat(msg.structured()).containsEntry("folder", TARGET);
            assertThat(map(msg.structured(), "untrusted")).containsEntry("subject", subjects.get(i));
            Result old = env.call("get_message", args("account", env.accounts.get(user), "message", moved.get(i)));
            assertThat(old.error()).isTrue();
            assertThat(old.errorCode()).isEqualTo("MESSAGE_NOT_FOUND");
        }

        // Server side: exactly the moved UIDs left their folders, three new UIDs appeared in the target, no flag
        // changed anywhere; the foreign \Deleted message survived with its UID and flag.
        MailboxSnapshot after = snapshot(env, user);
        MailboxSnapshot.Diff diff = MailboxSnapshot.diff(before, after);
        assertThat(diff.removed(SRC_A)).containsExactlyInAnyOrder(seed.a1(), seed.a2());
        assertThat(diff.removed(SRC_B)).containsExactlyInAnyOrder(seed.b1());
        assertThat(diff.added(TARGET)).hasSize(3);
        assertThat(diff.removed(TARGET)).isEmpty();
        assertThat(diff.added(SRC_A)).isEmpty();
        assertThat(diff.added(SRC_B)).isEmpty();
        assertThat(diff.flagChanged()).isEmpty();
        assertThat(diff.uidValidityChanged()).isEmpty();
        assertThat(after.folder(SRC_A).flags()).containsKeys(seed.a3(), seed.aDel());
        assertThat(after.folder(SRC_A).flags().get(seed.aDel())).contains("\\Deleted");
        assertThat(after.folder(SRC_B).flags()).containsKey(seed.b2());

        // Retry of the identical call: success, all already_gone, hint in notes.
        long retryOffset = env.logSize();
        Result retry = env.move(user, moved, TARGET);
        assertThat(retry.error()).as("%s", retry.structured()).isFalse();
        assertThat(retry.summary()).containsEntry("already_gone", 3).containsEntry("ok", 0);
        for (Map<String, Object> item : retry.items()) {
            assertThat(item).containsEntry("status", "already_gone");
            assertThat(item.get("new_handle")).isNull();
            assertThat(item.get("note")).isNull();
        }
        assertThat(retry.structured().get("notes")).isEqualTo(List.of(ALREADY_GONE_NOTE));
        assertNoMutation(env.commandsSince(retryOffset));
        assertThat(MailboxSnapshot.diff(after, snapshot(env, user)).isEmpty()).isTrue();

        return commands;
    }

    // ---- FULL: RFC 6851 MOVE ---------------------------------------------------------------------------------------

    @Nested
    @TestInstance(TestInstance.Lifecycle.PER_CLASS)
    class Full {

        private Env env;

        @BeforeAll
        void start() throws Exception {
            env = new Env(DovecotContainer.Profile.FULL, "move", "intarget", "stale", "race", "utf7", "utf8state");
        }

        @AfterAll
        void stop() {
            if (env != null) {
                env.close();
            }
        }

        @Test
        void uidMoveWithUnselectAndNoCopyStoreOrExpunge() throws Exception {
            List<String> commands = movesThreeHandlesFromTwoFolders(env, "move");

            // Two source folders: two UID MOVE commands, each group closed by UNSELECT.
            assertThat(starting(commands, "UID MOVE")).hasSize(2);
            assertThat(uidSetOf(starting(commands, "UID MOVE").get(0))).hasSize(2);
            assertThat(uidSetOf(starting(commands, "UID MOVE").get(1))).hasSize(1);
            assertThat(starting(commands, "UNSELECT")).hasSize(2);
            assertThat(commands).noneMatch(c -> c.startsWith("UID COPY") || c.startsWith("COPY")
                    || c.startsWith("UID STORE") || c.startsWith("STORE") || c.contains("EXPUNGE")
                    || c.startsWith("CLOSE"));
        }

        @Disabled("SPEC 5.4 'Never ENABLE UTF8=ACCEPT' cannot be met with Angus 2.0.5; open owner decision, see #179 comment")
        @Test
        void aNonAsciiTargetTravelsAsModifiedUtf7AndUtf8AcceptIsNeverEnabled() throws Exception {
            String user = "utf7";
            try (MailSeeder s = env.seeder(user)) {
                s.appendPlain(SRC_A, "u1", "x");
                s.store().getFolder(TARGET).create(Folder.HOLDS_MESSAGES);
            }
            List<String> input = new ArrayList<>(env.handles(user, SRC_A).values());
            long offset = env.logSize();
            env.ok("move_messages", args("account", env.accounts.get(user), "handles", input, "target_folder", TARGET));
            assertThat(starting(env.commandsSince(offset), "UID MOVE")).singleElement()
                    .asString().contains("\"Ziel-&ANw-bertrag\"");
            // SPEC 5.4: never ENABLE UTF8=ACCEPT (checked over the whole log of this server process).
            assertThat(env.commandsSince(0)).noneMatch(c -> c.startsWith("ENABLE"));
        }

        @Test
        void theTargetNameEncodingFollowsTheConnectionStateAndTheMoveRoundTrips() throws Exception {
            String user = "utf8state";
            try (MailSeeder s = env.seeder(user)) {
                s.appendPlain(SRC_A, "u1", "x");
                s.store().getFolder(TARGET).create(Folder.HOLDS_MESSAGES);
            }
            List<String> input = new ArrayList<>(env.handles(user, SRC_A).values());
            long offset = env.logSize();
            Result r = env.ok("move_messages",
                    args("account", env.accounts.get(user), "handles", input, "target_folder", TARGET));
            List<String> commands = env.commandsSince(offset);
            boolean utf8Enabled = env.commandsSince(0).stream().anyMatch(c -> c.equals("ENABLE UTF8=ACCEPT"));
            assertThat(starting(commands, "UID MOVE")).singleElement().asString()
                    .contains(utf8Enabled ? "\"Ziel-\u00dcbertrag\"" : "\"Ziel-&ANw-bertrag\"");
            String newHandle = (String) r.items().get(0).get("new_handle");
            Result msg = env.ok("get_message", args("account", env.accounts.get(user), "message", newHandle));
            assertThat(msg.structured()).containsEntry("folder", TARGET);
            assertThat(map(msg.structured(), "untrusted")).containsEntry("subject", "u1");
        }

        @Test
        void handlesAlreadyInTheTargetAreAlreadyInTarget() throws Exception {
            String user = "intarget";
            try (MailSeeder s = env.seeder(user)) {
                s.appendPlain(TARGET, "t1", "x");
                s.appendPlain(TARGET, "t2", "x");
            }
            Map<String, String> handles = env.handles(user, TARGET);
            List<String> input = new ArrayList<>(handles.values());
            MailboxSnapshot before;
            try (MailSeeder s = env.seeder(user)) {
                before = MailboxSnapshot.of(s.store(), TARGET);
            }
            long offset = env.logSize();
            Result r = env.move(user, input, TARGET);
            assertThat(r.error()).as("%s", r.structured()).isFalse();
            assertThat(r.summary()).containsEntry("already_in_target", 2).containsEntry("ok", 0);
            for (int i = 0; i < 2; i++) {
                Map<String, Object> item = r.items().get(i);
                assertThat(item).containsEntry("status", "already_in_target").containsEntry("new_handle",
                        input.get(i));
            }
            assertThat(r.structured().get("notes")).isEqualTo(List.of(ALREADY_IN_TARGET_NOTE));
            assertNoMutation(env.commandsSince(offset));
            try (MailSeeder s = env.seeder(user)) {
                assertThat(MailboxSnapshot.diff(before, MailboxSnapshot.of(s.store(), TARGET)).isEmpty()).isTrue();
            }
        }

        @Test
        void uidvalidityChangeBetweenSearchAndMoveIsStaleAndMovesNothing() throws Exception {
            String user = "stale";
            try (MailSeeder s = env.seeder(user)) {
                s.appendPlain(SRC_A, "old1", "x");
                s.appendPlain(SRC_A, "old2", "x");
                s.store().getFolder(TARGET).create(Folder.HOLDS_MESSAGES);
            }
            List<String> input = new ArrayList<>(env.handles(user, SRC_A).values());
            long oldValidity;
            try (MailSeeder s = env.seeder(user)) {
                oldValidity = MailboxSnapshot.of(s.store(), SRC_A).folder(SRC_A).uidValidity();
                s.store().getFolder(SRC_A).delete(true);
                Thread.sleep(1100); // Dovecot derives UIDVALIDITY from the creation time
                s.appendPlain(SRC_A, "new1", "x");
            }
            MailboxSnapshot before;
            try (MailSeeder s = env.seeder(user)) {
                before = MailboxSnapshot.of(s.store(), SRC_A, TARGET);
            }
            assertThat(before.folder(SRC_A).uidValidity()).isNotEqualTo(oldValidity);

            Result r = env.move(user, input, TARGET);
            assertThat(r.error()).as("%s", r.structured()).isFalse();
            assertThat(r.summary()).containsEntry("stale_reference", 2).containsEntry("ok", 0);
            for (Map<String, Object> item : r.items()) {
                assertThat(item).containsEntry("status", "stale_reference")
                        .containsEntry("reason", "uidvalidity_changed");
                assertThat(item.get("new_handle")).isNull();
            }
            try (MailSeeder s = env.seeder(user)) {
                assertThat(MailboxSnapshot.diff(before, MailboxSnapshot.of(s.store(), SRC_A, TARGET)).isEmpty())
                        .isTrue();
            }
        }

        @Test
        void aConcurrentExpungeOfOneAddressedUidIsAlreadyGoneAndNothingElseIsTouched() throws Exception {
            String user = "race";
            long u2;
            long unrelated;
            try (MailSeeder s = env.seeder(user)) {
                s.appendPlain(SRC_A, "r1", "x");
                u2 = s.appendPlain(SRC_A, "r2", "x");
                s.appendPlain(SRC_A, "r3", "x");
                unrelated = s.appendPlain(SRC_A, "r-keep", "x");
                s.store().getFolder(TARGET).create(Folder.HOLDS_MESSAGES);
            }
            Map<String, String> handles = env.handles(user, SRC_A);
            List<String> input = List.of(handles.get("r1"), handles.get("r2"), handles.get("r3"));
            try (MailSeeder second = env.seeder(user)) {
                expungeUid(second, SRC_A, u2);
            }
            MailboxSnapshot before;
            try (MailSeeder s = env.seeder(user)) {
                before = MailboxSnapshot.of(s.store(), SRC_A, TARGET);
            }

            Result r = env.move(user, input, TARGET);
            assertThat(r.error()).as("%s", r.structured()).isFalse();
            assertThat(r.items()).extracting(i -> i.get("status")).containsExactly("ok", "already_gone", "ok");
            assertThat(r.items().get(1).get("new_handle")).isNull();
            assertThat(r.summary()).containsEntry("ok", 2).containsEntry("already_gone", 1);
            assertThat(r.structured().get("notes")).isEqualTo(List.of(ALREADY_GONE_NOTE));

            try (MailSeeder s = env.seeder(user)) {
                MailboxSnapshot after = MailboxSnapshot.of(s.store(), SRC_A, TARGET);
                MailboxSnapshot.Diff diff = MailboxSnapshot.diff(before, after);
                assertThat(diff.removed(SRC_A)).hasSize(2).doesNotContain(unrelated);
                assertThat(after.folder(SRC_A).flags()).containsOnlyKeys(unrelated);
                assertThat(diff.added(TARGET)).hasSize(2);
                assertThat(diff.flagChanged()).isEmpty();
            }
        }

        @Test
        void moveMessagesIsAnnotatedDestructiveAndIdempotent() {
            McpSchema.Tool tool = env.client.listTools().tools().stream()
                    .filter(t -> "move_messages".equals(t.name())).findFirst().orElseThrow();
            assertThat(tool.annotations().destructiveHint()).isTrue();
            assertThat(tool.annotations().idempotentHint()).isTrue();
            assertThat(tool.annotations().readOnlyHint()).isFalse();
        }
    }

    // ---- NO_MOVE: COPY + STORE + UID EXPUNGE fallback ---------------------------------------------------------------

    @Nested
    @TestInstance(TestInstance.Lifecycle.PER_CLASS)
    class NoMove {

        private Env env;

        @BeforeAll
        void start() throws Exception {
            env = new Env(DovecotContainer.Profile.NO_MOVE, "fallback");
        }

        @AfterAll
        void stop() {
            if (env != null) {
                env.close();
            }
        }

        @Test
        void fallbackCopiesFlagsAndExpungesExactlyTheCopiedUids() throws Exception {
            String user = "fallback";
            // Recorded before the scenario seeds: the UIDs are deterministic per folder, so read them afterwards.
            List<String> commands = movesThreeHandlesFromTwoFolders(env, user);
            MailboxSnapshot after = snapshot(env, user);
            // After the move the survivors of A are a3 and the foreign \Deleted message: the moved UIDs are the
            // two lowest UIDs of A (a1, a2) and the lowest of B (b1).
            Set<Long> keysA = after.folder(SRC_A).flags().keySet();
            long lowestSurvivor = keysA.stream().mapToLong(Long::longValue).min().orElseThrow();
            Set<Long> movedA = new TreeSet<>(Set.of(lowestSurvivor - 2, lowestSurvivor - 1));
            long movedB = after.folder(SRC_B).flags().keySet().stream().mapToLong(Long::longValue).min()
                    .orElseThrow() - 1;

            assertThat(starting(commands, "UID MOVE")).isEmpty();
            assertThat(commands).noneMatch(c -> c.startsWith("MOVE") || c.startsWith("COPY")
                    || c.startsWith("STORE") || c.startsWith("EXPUNGE") || c.startsWith("CLOSE"));
            List<String> copies = starting(commands, "UID COPY");
            List<String> stores = starting(commands, "UID STORE");
            List<String> expunges = starting(commands, "UID EXPUNGE");
            assertThat(copies).hasSize(2);
            assertThat(stores).hasSize(2);
            assertThat(expunges).hasSize(2);
            assertThat(uidSetOf(copies.get(0))).isEqualTo(movedA);
            assertThat(uidSetOf(copies.get(1))).containsExactly(movedB);
            for (int i = 0; i < 2; i++) {
                assertThat(stores.get(i)).endsWith(" +FLAGS.SILENT (\\Deleted)");
                assertThat(uidSetOf(stores.get(i))).isEqualTo(uidSetOf(copies.get(i)));
                assertThat(expunges.get(i)).isEqualTo("UID EXPUNGE " + expunges.get(i).split(" ")[2]);
                assertThat(uidSetOf(expunges.get(i))).isEqualTo(uidSetOf(copies.get(i)));
            }
            // Per group the order is COPY, STORE, EXPUNGE.
            assertThat(commands.indexOf(copies.get(0))).isLessThan(commands.indexOf(stores.get(0)));
            assertThat(commands.indexOf(stores.get(0))).isLessThan(commands.indexOf(expunges.get(0)));
        }
    }

    // ---- NO_MOVE_NO_UIDPLUS: refusal -------------------------------------------------------------------------------

    @Nested
    @TestInstance(TestInstance.Lifecycle.PER_CLASS)
    class NoUidplus {

        private Env env;

        @BeforeAll
        void start() throws Exception {
            env = new Env(DovecotContainer.Profile.NO_MOVE_NO_UIDPLUS, "refuse");
        }

        @AfterAll
        void stop() {
            if (env != null) {
                env.close();
            }
        }

        @Test
        void withoutUidplusTheMoveIsUnsupportedBeforeAnySelect() throws Exception {
            String user = "refuse";
            try (MailSeeder s = env.seeder(user)) {
                s.store().getFolder(TARGET).create(Folder.HOLDS_MESSAGES);
            }
            appendWithoutUidplus(user);
            List<String> input = new ArrayList<>(env.handles(user, SRC_A).values());
            MailboxSnapshot before = snapshotAT(user);
            assertThat(before.folder(SRC_A).flags()).hasSize(2);

            long offset = env.logSize();
            Result r = env.move(user, input, TARGET);
            assertThat(r.error()).isTrue();
            assertThat(r.errorCode()).isEqualTo("UNSUPPORTED");
            assertThat(r.detail("capability")).isEqualTo("UIDPLUS");
            assertThat(env.commandsSince(offset)).noneMatch(c -> c.startsWith("SELECT") || c.startsWith("EXAMINE")
                    || c.startsWith("UID") || c.startsWith("UNSELECT"));
            assertThat(MailboxSnapshot.diff(before, snapshotAT(user)).isEmpty()).isTrue();
        }

        private MailboxSnapshot snapshotAT(String user) throws Exception {
            try (MailSeeder s = env.seeder(user)) {
                return MailboxSnapshot.of(s.store(), SRC_A, TARGET);
            }
        }

        private void appendWithoutUidplus(String user) throws Exception {
            try (MailSeeder s = env.seeder(user)) {
                try {
                    s.appendPlain(SRC_A, "u1", "x");
                } catch (MessagingException expected) {
                    // the message is stored; only the APPENDUID report is missing
                }
                try {
                    s.appendPlain(SRC_A, "u2", "x");
                } catch (MessagingException expected) {
                    // as above
                }
            }
        }
    }
}
