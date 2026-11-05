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
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeMessage;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.eclipse.angus.mail.imap.IMAPFolder;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * {@code delete_messages} against Dovecot 2.4.5 in the three capability sets (SPEC 4 "v2 write contract", 5.4, 5.8):
 * a delete is a move to the SPECIAL-USE {@code \Trash} folder and never a permanent deletion. The tools are driven
 * through the stdio client against the shaded jar with {@code imap.trace: true}; the trace (logger
 * {@code imapmcp.imap.trace}) is the wire evidence. One server process serves all three containers, one account (and
 * Dovecot user, i.e. own store) per scenario.
 */
@Testcontainers
class DeleteMessagesDovecotIT {

    /** A client command line of the trace: tag, command, rest. Server responses ("A1 OK ...") are filtered out. */
    private static final Pattern CLIENT_COMMAND =
            Pattern.compile(" imapmcp\\.imap\\.trace - ([A-Z]+\\d+) (?!(?:OK|NO|BAD)\\b)([A-Z][A-Z.]*\\b.*)$");

    private static final String ALREADY_IN_TRASH =
            "Some messages were already in Trash and were left unchanged.";
    private static final String ALREADY_GONE = "Some messages are no longer in their source folder; if this call "
            + "repeats an earlier one, they were already moved. Search the target folder for their new handles.";

    @Container
    static final DovecotContainer FULL = new DovecotContainer(DovecotContainer.Profile.FULL);
    @Container
    static final DovecotContainer NO_MOVE = new DovecotContainer(DovecotContainer.Profile.NO_MOVE);
    @Container
    static final DovecotContainer NO_UIDPLUS = new DovecotContainer(DovecotContainer.Profile.NO_MOVE_NO_UIDPLUS);

    @TempDir
    static Path dir;

    private static ServerConfig config;
    private static McpSyncClient client;
    private static final Map<String, String> accountHandles = new LinkedHashMap<>();
    private static final Map<String, DovecotContainer> containerOf = new LinkedHashMap<>();

    @BeforeAll
    static void start() throws Exception {
        ServerConfigWriter writer = new ServerConfigWriter(dir).trustStore(TestTrust.writeTruststore(dir))
                .extraYaml("imap:\n  trace: true\n");
        for (String id : List.of("del", "undo", "intrash", "retry", "foreign")) {
            add(writer, id, FULL);
        }
        add(writer, "fallback", NO_MOVE);
        add(writer, "refused", NO_UIDPLUS);
        config = writer.write();
        List<String> cmd = JarLauncher.builder(config, "stdio").command();
        ServerParameters params = ServerParameters.builder(cmd.get(0)).args(cmd.subList(1, cmd.size()))
                .env(config.env()).build();
        client = McpClient.sync(new StdioClientTransport(params, McpJsonDefaults.getMapper()))
                .requestTimeout(Duration.ofSeconds(60)).initializationTimeout(Duration.ofSeconds(60)).build();
        client.initialize();
        for (Map<String, Object> a : list(call("list_accounts", Map.of()).structured, "accounts")) {
            accountHandles.put((String) a.get("label"), (String) a.get("account"));
        }
    }

    private static void add(ServerConfigWriter writer, String id, DovecotContainer c) {
        containerOf.put(id, c);
        writer.account(id, c.getHost(), c.imapsPort(), MailAccount.Security.IMPLICIT_TLS, "del-" + id,
                DovecotContainer.PASSWORD);
    }

    @AfterAll
    static void stop() {
        if (client != null) {
            client.close();
        }
    }

    // ---- helpers -------------------------------------------------------------------------------------------

    private record Result(boolean error, Map<String, Object> structured) {

        String errorCode() {
            return (String) map(structured, "error").get("code");
        }

        Object detail(String key) {
            return map(map(structured, "error"), "details").get(key);
        }
    }

    @SuppressWarnings("unchecked")
    private static Result call(String tool, Map<String, Object> args) {
        McpSchema.CallToolResult r = client.callTool(new McpSchema.CallToolRequest(tool, args));
        return new Result(Boolean.TRUE.equals(r.isError()), ToolResults.payload(r));
    }

    private static Result ok(String tool, Map<String, Object> args) {
        Result r = call(tool, args);
        assertThat(r.error()).as("%s -> %s", tool, r.structured()).isFalse();
        return r;
    }

    private static Map<String, Object> args(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            m.put((String) kv[i], kv[i + 1]);
        }
        return m;
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> list(Map<String, Object> m, String key) {
        return (List<Map<String, Object>>) m.get(key);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> map(Map<String, Object> m, String key) {
        return (Map<String, Object>) m.get(key);
    }

    private static String account(String id) {
        return accountHandles.get(id);
    }

    private static MailSeeder seeder(String id) throws MessagingException {
        return containerOf.get(id).seeder("del-" + id);
    }

    /** The folder carrying {@code \Trash}, found through list_folders (never by name). */
    private static String trashFolder(String id) {
        Result folders = ok("list_folders", args("account", account(id)));
        List<String> trash = list(folders.structured, "folders").stream().filter(f -> "trash".equals(f.get("role")))
                .map(f -> (String) f.get("folder")).toList();
        assertThat(trash).hasSize(1);
        return trash.get(0);
    }

    /** Handles of the messages of {@code folder} by subject. */
    private static Map<String, String> handlesBySubject(String id, String folder) {
        Result search = ok("search_messages", args("account", account(id), "folder", folder, "page_size", 100));
        Map<String, String> out = new LinkedHashMap<>();
        for (Map<String, Object> m : list(search.structured, "messages")) {
            out.put((String) map(m, "untrusted").get("subject"), (String) m.get("handle"));
        }
        return out;
    }

    private static Result delete(String id, String... handles) {
        return call("delete_messages", args("account", account(id), "handles", List.of(handles)));
    }

    private static List<String> statuses(Result r) {
        return list(r.structured, "results").stream().map(i -> (String) i.get("status")).toList();
    }

    private static List<String> newHandles(Result r) {
        return list(r.structured, "results").stream().map(i -> (String) i.get("new_handle")).toList();
    }

    @SuppressWarnings("unchecked")
    private static List<String> notes(Result r) {
        return (List<String>) r.structured.get("notes");
    }

    private static int summary(Result r, String key) {
        return ((Number) map(r.structured, "summary").get(key)).intValue();
    }

    private static long logSize() throws IOException {
        return Files.exists(config.logFile()) ? Files.size(config.logFile()) : 0;
    }

    /** Client commands (tag stripped) the server sent since {@code offset}. */
    private static List<String> commandsSince(long offset) throws IOException {
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

    /** The wire rules of v2 that hold on every profile: no plain EXPUNGE, no CLOSE, no DELETE, only UID commands. */
    private static void assertNoForbiddenCommands(List<String> commands) {
        assertThat(commands).noneMatch(c -> c.equals("EXPUNGE") || c.startsWith("EXPUNGE "));
        assertThat(commands).noneMatch(c -> c.equals("CLOSE"));
        assertThat(commands).noneMatch(c -> c.startsWith("DELETE ") || c.startsWith("RENAME "));
        assertThat(commands).noneMatch(c -> c.startsWith("COPY ") || c.startsWith("MOVE ") || c.startsWith("STORE "));
    }

    private static boolean mutating(String command) {
        return command.startsWith("UID MOVE") || command.startsWith("UID COPY") || command.startsWith("UID STORE")
                || command.contains("EXPUNGE") || command.startsWith("APPEND") || command.startsWith("CREATE")
                || command.startsWith("DELETE") || command.startsWith("RENAME");
    }

    /** Expands a UID set such as {@code 4:6,9} (also the reversed range form). */
    private static Set<Long> uidSet(String set) {
        Set<Long> out = new TreeSet<>();
        for (String part : set.split(",")) {
            String[] bounds = part.split(":");
            long a = Long.parseLong(bounds[0]);
            long b = Long.parseLong(bounds[bounds.length - 1]);
            for (long u = Math.min(a, b); u <= Math.max(a, b); u++) {
                out.add(u);
            }
        }
        return out;
    }

    private static void markDeleted(MailSeeder s, String folder, long uid) throws MessagingException {
        IMAPFolder f = (IMAPFolder) s.store().getFolder(folder);
        f.open(Folder.READ_WRITE);
        try {
            f.getMessageByUID(uid).setFlag(Flags.Flag.DELETED, true);
        } finally {
            f.close(false);
        }
    }

    /** Appends without APPENDUID (the no-UIDPLUS profile does not report it). */
    private static void appendRaw(MailSeeder s, String folder, String subject) throws MessagingException {
        MimeMessage m = new MimeMessage(jakarta.mail.Session.getInstance(new Properties()));
        m.setFrom(new InternetAddress("sender@example.org"));
        m.setRecipient(Message.RecipientType.TO, new InternetAddress("recipient@example.org"));
        m.setSubject(subject, "UTF-8");
        m.setText("body", "UTF-8");
        s.store().getFolder(folder).appendMessages(new Message[] {m});
    }

    // ---- tests ---------------------------------------------------------------------------------------------

    @Test
    void deleteMovesToTheTrashRoleAndLeavesAForeignDeletedMessageAlone() throws Exception {
        long u1;
        long u2;
        long foreign;
        MailboxSnapshot before;
        String trash = trashFolder("foreign");
        try (MailSeeder s = seeder("foreign")) {
            u1 = s.appendPlain("INBOX", "one", "body");
            u2 = s.appendPlain("INBOX", "two", "body");
            s.appendPlain("INBOX", "keep", "body");
            foreign = s.appendPlain("INBOX", "foreign", "body");
            markDeleted(s, "INBOX", foreign);
            before = MailboxSnapshot.of(s.store(), "INBOX", trash);
        }
        Map<String, String> handles = handlesBySubject("foreign", "INBOX");
        long log = logSize();
        Result r = delete("foreign", handles.get("one"), handles.get("two"));
        List<String> commands = commandsSince(log);

        assertThat(r.error()).as("%s", r.structured()).isFalse();
        assertThat(r.structured).containsEntry("target_folder", trash);
        assertThat(statuses(r)).containsExactly("ok", "ok");
        assertThat(summary(r, "ok")).isEqualTo(2);
        assertThat(notes(r)).isEmpty();
        assertThat(newHandles(r)).doesNotContainNull();

        try (MailSeeder s = seeder("foreign")) {
            MailboxSnapshot.Diff diff = MailboxSnapshot.diff(before, MailboxSnapshot.of(s.store(), "INBOX", trash));
            assertThat(diff.removed("INBOX")).containsExactlyInAnyOrder(u1, u2);
            assertThat(diff.added("INBOX")).isEmpty();
            assertThat(diff.flagChanged("INBOX")).isEmpty();
            assertThat(diff.added(trash)).hasSize(2);
            assertThat(diff.removed(trash)).isEmpty();
            assertThat(diff.uidValidityChanged()).isEmpty();
            MailboxSnapshot after = MailboxSnapshot.of(s.store(), "INBOX");
            assertThat(after.folder("INBOX").flags().get(foreign)).contains("\\Deleted");
        }
        // The returned handles are Trash handles of exactly the moved messages.
        Map<String, String> inTrash = handlesBySubject("foreign", trash);
        assertThat(newHandles(r)).containsExactlyInAnyOrder(inTrash.get("one"), inTrash.get("two"));

        assertThat(commands).anyMatch(c -> c.startsWith("UID MOVE "));
        assertNoForbiddenCommands(commands);
        assertThat(commands).noneMatch(c -> c.contains("EXPUNGE"));
    }

    @Test
    void movingTheReturnedTrashHandlesBackUndoesTheDelete() throws Exception {
        String trash = trashFolder("undo");
        MailboxSnapshot before;
        try (MailSeeder s = seeder("undo")) {
            s.appendPlain("INBOX", "a", "body");
            s.appendPlain("INBOX", "b", "body");
            before = MailboxSnapshot.of(s.store(), "INBOX", trash);
        }
        Map<String, String> handles = handlesBySubject("undo", "INBOX");
        Result deleted = delete("undo", handles.get("a"), handles.get("b"));
        assertThat(statuses(deleted)).containsExactly("ok", "ok");

        Result undo = call("move_messages",
                args("account", account("undo"), "handles", newHandles(deleted), "target_folder", "INBOX"));
        assertThat(undo.error()).as("%s", undo.structured).isFalse();
        assertThat(statuses(undo)).containsExactly("ok", "ok");

        assertThat(handlesBySubject("undo", "INBOX")).containsOnlyKeys("a", "b");
        try (MailSeeder s = seeder("undo")) {
            MailboxSnapshot.Diff diff = MailboxSnapshot.diff(before, MailboxSnapshot.of(s.store(), "INBOX", trash));
            assertThat(diff.added("INBOX")).hasSize(2);
            assertThat(diff.removed("INBOX")).hasSize(2);
            assertThat(s.messageCount(trash)).isZero();
        }
    }

    @Test
    void deletingHandlesThatAreAlreadyInTrashSendsNothingAndExpungesNothing() throws Exception {
        String trash = trashFolder("intrash");
        MailboxSnapshot before;
        try (MailSeeder s = seeder("intrash")) {
            s.appendPlain(trash, "t1", "body");
            s.appendPlain(trash, "t2", "body");
            before = MailboxSnapshot.of(s.store(), "INBOX", trash);
        }
        Map<String, String> handles = handlesBySubject("intrash", trash);
        long log = logSize();
        Result r = delete("intrash", handles.get("t1"), handles.get("t2"));
        List<String> commands = commandsSince(log);

        assertThat(r.error()).as("%s", r.structured).isFalse();
        assertThat(statuses(r)).containsExactly("already_in_target", "already_in_target");
        assertThat(newHandles(r)).containsExactly(handles.get("t1"), handles.get("t2"));
        assertThat(summary(r, "already_in_target")).isEqualTo(2);
        assertThat(notes(r)).containsExactly(ALREADY_IN_TRASH);
        assertThat(commands).noneMatch(DeleteMessagesDovecotIT::mutating);
        assertNoForbiddenCommands(commands);
        try (MailSeeder s = seeder("intrash")) {
            assertThat(MailboxSnapshot.diff(before, MailboxSnapshot.of(s.store(), "INBOX", trash)).isEmpty())
                    .isTrue();
            assertThat(s.messageCount(trash)).isEqualTo(2);
        }
    }

    @Test
    void repeatingASuccessfulDeleteReportsAlreadyGone() throws Exception {
        try (MailSeeder s = seeder("retry")) {
            s.appendPlain("INBOX", "r1", "body");
            s.appendPlain("INBOX", "r2", "body");
        }
        Map<String, String> handles = handlesBySubject("retry", "INBOX");
        Result first = delete("retry", handles.get("r1"), handles.get("r2"));
        assertThat(statuses(first)).containsExactly("ok", "ok");

        long log = logSize();
        Result second = delete("retry", handles.get("r1"), handles.get("r2"));
        List<String> commands = commandsSince(log);
        assertThat(second.error()).as("%s", second.structured).isFalse();
        assertThat(statuses(second)).containsExactly("already_gone", "already_gone");
        assertThat(newHandles(second)).containsOnlyNulls();
        assertThat(summary(second, "already_gone")).isEqualTo(2);
        assertThat(notes(second)).containsExactly(ALREADY_GONE);
        assertThat(commands).noneMatch(DeleteMessagesDovecotIT::mutating);
        assertNoForbiddenCommands(commands);
        try (MailSeeder s = seeder("retry")) {
            assertThat(s.messageCount(trashFolder("retry"))).isEqualTo(2);
        }
    }

    @Test
    void withoutMoveTheFallbackExpungesExactlyTheCopiedUids() throws Exception {
        String trash = trashFolder("fallback");
        long u1;
        long u2;
        long foreign;
        MailboxSnapshot before;
        try (MailSeeder s = seeder("fallback")) {
            u1 = s.appendPlain("INBOX", "f1", "body");
            s.appendPlain("INBOX", "keep", "body");
            u2 = s.appendPlain("INBOX", "f2", "body");
            foreign = s.appendPlain("INBOX", "foreign", "body");
            markDeleted(s, "INBOX", foreign);
            before = MailboxSnapshot.of(s.store(), "INBOX", trash);
        }
        Map<String, String> handles = handlesBySubject("fallback", "INBOX");
        long log = logSize();
        Result r = delete("fallback", handles.get("f1"), handles.get("f2"));
        List<String> commands = commandsSince(log);

        assertThat(r.error()).as("%s", r.structured).isFalse();
        assertThat(statuses(r)).containsExactly("ok", "ok");
        assertThat(newHandles(r)).doesNotContainNull();

        try (MailSeeder s = seeder("fallback")) {
            MailboxSnapshot after = MailboxSnapshot.of(s.store(), "INBOX", trash);
            MailboxSnapshot.Diff diff = MailboxSnapshot.diff(before, after);
            assertThat(diff.removed("INBOX")).containsExactlyInAnyOrder(u1, u2);
            assertThat(diff.added(trash)).hasSize(2);
            assertThat(after.folder("INBOX").flags().get(foreign)).contains("\\Deleted");
        }

        assertThat(commands).noneMatch(c -> c.startsWith("UID MOVE"));
        assertThat(commands).anyMatch(c -> c.startsWith("UID COPY "));
        assertThat(commands).anyMatch(c -> c.startsWith("UID STORE ") && c.contains("+FLAGS.SILENT")
                && c.contains("\\Deleted"));
        List<String> expunges = commands.stream().filter(c -> c.contains("EXPUNGE")).toList();
        assertThat(expunges).hasSize(1);
        assertThat(expunges.get(0)).startsWith("UID EXPUNGE ");
        assertThat(uidSet(expunges.get(0).substring("UID EXPUNGE ".length()).strip()))
                .containsExactlyInAnyOrder(u1, u2);
        assertNoForbiddenCommands(commands);
    }

    @Test
    void withoutUidplusTheDeleteIsRefusedAndNothingChanges() throws Exception {
        String trash = trashFolder("refused");
        MailboxSnapshot before;
        try (MailSeeder s = seeder("refused")) {
            appendRaw(s, "INBOX", "x1");
            appendRaw(s, "INBOX", "x2");
            before = MailboxSnapshot.of(s.store(), "INBOX", trash);
        }
        Map<String, String> handles = handlesBySubject("refused", "INBOX");
        long log = logSize();
        Result r = delete("refused", handles.get("x1"), handles.get("x2"));
        List<String> commands = commandsSince(log);

        assertThat(r.error()).isTrue();
        assertThat(r.errorCode()).isEqualTo("UNSUPPORTED");
        assertThat(r.detail("capability")).isEqualTo("UIDPLUS");
        assertThat(commands).noneMatch(DeleteMessagesDovecotIT::mutating);
        assertThat(commands).noneMatch(c -> c.startsWith("SELECT"));
        assertNoForbiddenCommands(commands);
        try (MailSeeder s = seeder("refused")) {
            assertThat(MailboxSnapshot.diff(before, MailboxSnapshot.of(s.store(), "INBOX", trash)).isEmpty())
                    .isTrue();
        }
    }
}
