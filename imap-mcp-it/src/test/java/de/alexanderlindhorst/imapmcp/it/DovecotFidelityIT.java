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
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.Set;
import org.eclipse.angus.mail.imap.IMAPFolder;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Protocol fidelity of the core tools against a real Dovecot 2.4.5 (SPEC 5.8, 5.4): capability detection,
 * SPECIAL-USE roles, UIDVALIDITY/expunge/folder-deleted handling, PEEK and read-only access on the wire and a constant
 * IMAP command count per search page.
 *
 * <p>The tools are driven through the stdio client against the shaded jar with {@code imap.trace: true}. The trace
 * (logger {@code imapmcp.imap.trace}, written to the server log file) is the "client debug stream" of AC2: Dovecot's
 * own log cannot be tuned in this image, but the trace shows every command our session sent. Each wire test
 * reads only the log bytes written during its own calls. Every scenario uses its own Dovecot user (own store) and
 * its own configured account, all in one server process.
 */
@Testcontainers
class DovecotFidelityIT {

    /** A client command line of the trace: tag, command, rest. Server responses ("A1 OK ...") are filtered out. */
    private static final Pattern CLIENT_COMMAND =
            Pattern.compile(" imapmcp\\.imap\\.trace - ([A-Z]+\\d+) (?!(?:OK|NO|BAD)\\b)([A-Z][A-Z.]*\\b.*)$");

    @Container
    static final DovecotContainer DOVECOT = new DovecotContainer();

    @TempDir
    static Path dir;

    private static ServerConfig config;
    private static McpSyncClient client;
    private static Map<String, String> accountHandles = new LinkedHashMap<>();

    @BeforeAll
    static void start() throws Exception {
        ServerConfigWriter writer = new ServerConfigWriter(dir).trustStore(TestTrust.writeTruststore(dir))
                .extraYaml("imap:\n  trace: true\n");
        for (String id : List.of("caps", "roles", "plain", "stale", "gone", "expunged", "peek", "count")) {
            writer.account(id, DOVECOT.getHost(), DOVECOT.imapsPort(), MailAccount.Security.IMPLICIT_TLS, "fid-" + id,
                    DovecotContainer.PASSWORD);
        }
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
        return DOVECOT.seeder("fid-" + id);
    }

    private static String folderWithAttribute(MailSeeder seeder, String attribute) throws MessagingException {
        for (Folder f : seeder.store().getDefaultFolder().list("*")) {
            if (List.of(((IMAPFolder) f).getAttributes()).contains(attribute)) {
                return f.getFullName();
            }
        }
        throw new IllegalStateException("no folder with " + attribute);
    }

    private static String firstHandle(Result search) {
        return (String) list(search.structured, "messages").get(0).get("handle");
    }

    private static long logSize() throws IOException {
        return Files.exists(config.logFile()) ? Files.size(config.logFile()) : 0;
    }

    /** Client commands (tag stripped, e.g. {@code UID FETCH 1:3 (...)}) the server sent since {@code offset}. */
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

    // ---- tests ---------------------------------------------------------------------------------------------

    @Test
    void capabilityDetectionReportsMoveUidplusSortSpecialUseAndNotGmail() throws Exception {
        try (MailSeeder s = seeder("caps")) {
            s.appendPlain("INBOX", "x", "x");
        }
        Result r = ok("list_accounts", args());
        Map<String, Object> caps = list(r.structured, "accounts").stream()
                .filter(a -> account("caps").equals(a.get("account"))).findFirst().map(a -> map(a, "capabilities"))
                .orElseThrow();
        assertThat(caps).containsEntry("move", true).containsEntry("uidplus", true).containsEntry("sort", true)
                .containsEntry("special_use", true).containsEntry("gmail", false);
    }


    @Test
    void rolesComeFromSpecialUseAttributesAndResolveThroughFolderRole() throws Exception {
        Map<String, String> folderByRole = new LinkedHashMap<>();
        try (MailSeeder s = seeder("roles")) {
            for (Map.Entry<String, String> e : Map.of("sent", "\\Sent", "trash", "\\Trash", "drafts", "\\Drafts",
                    "junk", "\\Junk", "archive", "\\Archive").entrySet()) {
                String folder = folderWithAttribute(s, e.getValue());
                folderByRole.put(e.getKey(), folder);
                s.appendPlain(folder, "in-" + e.getKey(), "body");
            }
        }
        long before = logSize();
        Result folders = ok("list_folders", args("account", account("roles")));
        Map<String, String> reported = new LinkedHashMap<>();
        for (Map<String, Object> f : list(folders.structured, "folders")) {
            if (f.get("role") != null) {
                reported.put((String) f.get("role"), (String) f.get("folder"));
            }
        }
        assertThat(reported).containsAllEntriesOf(folderByRole);
        // #21: Angus issues a plain LIST (no RETURN (SPECIAL-USE)); Dovecot still reports the attributes.
        assertThat(commandsSince(before)).contains("LIST \"\" \"*\"").noneMatch(c -> c.contains("RETURN"));

        for (String role : folderByRole.keySet()) {
            Result r = ok("search_messages", args("account", account("roles"), "folder_role", role));
            assertThat(r.structured).containsEntry("folder", folderByRole.get(role));
            assertThat(list(r.structured, "messages")).hasSize(1);
            assertThat(map(list(r.structured, "messages").get(0), "untrusted")).containsEntry("subject", "in-" + role);
        }
    }

    @Test
    void aFolderWithoutTheSpecialUseAttributeIsNeverReportedAsARole() throws Exception {
        // The image gives \\Sent to the mailbox named "Sent" by configuration and re-creates it (auto = create), so the
        // attribute cannot be removed from it. Instead the user gets look-alike folders named like sent folders
        // (including a nested one called "Sent") that carry no attribute: they must have no role, and "sent" must
        // resolve to the one folder that has the attribute.
        String delimiter;
        try (MailSeeder s = seeder("plain")) {
            delimiter = String.valueOf(s.store().getDefaultFolder().getSeparator());
            for (String name : List.of("Sent Items", "Gesendet", "Sent Mail", "Projects", "Projects" + delimiter + "Sent")) {
                s.store().getFolder(name).create(Folder.HOLDS_MESSAGES);
            }
            s.appendPlain("Sent Items", "lookalike", "x");
            s.appendPlain("Sent", "real", "x");
        }
        Result folders = ok("list_folders", args("account", account("plain")));
        Map<String, Object> roleByFolder = new LinkedHashMap<>();
        for (Map<String, Object> f : list(folders.structured, "folders")) {
            roleByFolder.put((String) f.get("folder"), f.get("role"));
        }
        assertThat(roleByFolder).containsEntry("Sent", "sent");
        for (String lookalike : List.of("Sent Items", "Gesendet", "Sent Mail", "Projects", "Projects" + delimiter + "Sent")) {
            assertThat(roleByFolder).as(lookalike).containsEntry(lookalike, null);
        }
        assertThat(roleByFolder.values().stream().filter("sent"::equals)).hasSize(1);

        Result bySent = ok("search_messages", args("account", account("plain"), "folder_role", "sent"));
        assertThat(bySent.structured).containsEntry("folder", "Sent");
        assertThat(list(bySent.structured, "messages")).hasSize(1);
    }

    @Test
    void messageHandleAndCursorGoStaleWhenUidvalidityChanges() throws Exception {
        try (MailSeeder s = seeder("stale")) {
            for (int i = 0; i < 3; i++) {
                s.appendPlain("Work", "w" + i, "body " + i);
            }
        }
        Result first = ok("search_messages", args("account", account("stale"), "folder", "Work", "page_size", 1));
        String handle = firstHandle(first);
        String cursor = (String) first.structured.get("next_cursor");
        assertThat(cursor).isNotNull();
        ok("get_message", args("account", account("stale"), "message", handle));

        recreate("stale", "Work", 3);

        Result msg = call("get_message", args("account", account("stale"), "message", handle));
        assertThat(msg.error()).isTrue();
        assertThat(msg.errorCode()).isEqualTo("STALE_REFERENCE");
        assertThat(msg.detail("reason")).isEqualTo("uidvalidity_changed");
        Result att = call("list_attachments", args("account", account("stale"), "message", handle));
        assertThat(att.errorCode()).isEqualTo("STALE_REFERENCE");

        Result page = call("search_messages",
                args("account", account("stale"), "folder", "Work", "page_size", 1, "cursor", cursor));
        assertThat(page.error()).isTrue();
        assertThat(page.errorCode()).isEqualTo("STALE_REFERENCE");
        assertThat(page.detail("reason")).isEqualTo("uidvalidity_changed");

        // A fresh search recovers.
        Result fresh = ok("search_messages", args("account", account("stale"), "folder", "Work"));
        assertThat(((Number) fresh.structured.get("total")).intValue()).isEqualTo(3);
    }

    @Test
    void handleOfADeletedFolderIsStaleWithFolderMissing() throws Exception {
        try (MailSeeder s = seeder("gone")) {
            s.appendPlain("Vanishing", "v", "body");
        }
        String handle = firstHandle(
                ok("search_messages", args("account", account("gone"), "folder", "Vanishing")));
        try (MailSeeder s = seeder("gone")) {
            s.store().getFolder("Vanishing").delete(true);
        }
        Result msg = call("get_message", args("account", account("gone"), "message", handle));
        assertThat(msg.error()).isTrue();
        assertThat(msg.errorCode()).isEqualTo("STALE_REFERENCE");
        assertThat(msg.detail("reason")).isEqualTo("folder_missing");
    }

    @Test
    void anExpungedMessageIsMessageNotFound() throws Exception {
        long gone;
        try (MailSeeder s = seeder("expunged")) {
            s.appendPlain("Box", "keep", "body");
            gone = s.appendPlain("Box", "drop", "body");
        }
        Result search = ok("search_messages", args("account", account("expunged"), "folder", "Box"));
        String droppedHandle = list(search.structured, "messages").stream()
                .filter(m -> "drop".equals(map(m, "untrusted").get("subject"))).findFirst().orElseThrow()
                .get("handle").toString();
        // Test fixture only: expunge exactly that UID (UID EXPUNGE) on a separate connection.
        try (MailSeeder s = seeder("expunged")) {
            IMAPFolder f = (IMAPFolder) s.store().getFolder("Box");
            f.open(Folder.READ_WRITE);
            try {
                Message m = f.getMessageByUID(gone);
                m.setFlag(Flags.Flag.DELETED, true);
                f.expunge(new Message[] {m});
            } finally {
                f.close(false);
            }
        }
        Result msg = call("get_message", args("account", account("expunged"), "message", droppedHandle));
        assertThat(msg.error()).isTrue();
        assertThat(msg.errorCode()).isEqualTo("MESSAGE_NOT_FOUND");
        assertThat(call("list_attachments", args("account", account("expunged"), "message", droppedHandle))
                .errorCode()).isEqualTo("MESSAGE_NOT_FOUND");
    }

    @Test
    void readToolsPeekOnTheWireAndLeaveEveryMessageUntouched() throws Exception {
        try (MailSeeder s = seeder("peek")) {
            s.appendPlain("INBOX", "plain", "plain body");
            s.appendMultipart("INBOX", "multi", "text part", "<p>html part</p>",
                    List.of(new MailSeeder.Attachment("a.pdf", "application/pdf", new byte[] {1, 2, 3})));
        }
        long before = logSize();
        Result search = ok("search_messages", args("account", account("peek"), "folder", "INBOX"));
        for (Map<String, Object> m : list(search.structured, "messages")) {
            String handle = (String) m.get("handle");
            ok("get_message", args("account", account("peek"), "message", handle, "include_headers", true));
            ok("list_attachments", args("account", account("peek"), "message", handle));
        }
        ok("list_folders", args("account", account("peek")));
        List<String> commands = commandsSince(before);

        // Never a read-write open, never a state change, never an EXPUNGE, only BODY.PEEK sections.
        assertThat(commands).anyMatch(c -> c.startsWith("EXAMINE "));
        assertThat(commands).noneMatch(c -> c.startsWith("SELECT"));
        assertThat(commands).noneMatch(c -> c.startsWith("STORE") || c.startsWith("UID STORE"));
        assertThat(commands).noneMatch(c -> c.contains("EXPUNGE"));
        assertThat(commands).noneMatch(c -> c.startsWith("APPEND") || c.startsWith("COPY") || c.startsWith("MOVE")
                || c.startsWith("UID COPY") || c.startsWith("UID MOVE") || c.startsWith("CREATE")
                || c.startsWith("DELETE") || c.startsWith("RENAME"));
        List<String> fetches = commands.stream().filter(c -> c.contains("FETCH")).toList();
        assertThat(fetches).isNotEmpty();
        assertThat(fetches).noneMatch(c -> c.contains("BODY[") || c.contains("RFC822.TEXT")
                || c.contains("RFC822.HEADER") || c.matches(".*\\bRFC822[ )].*"));
        assertThat(fetches).anyMatch(c -> c.contains("BODY.PEEK["));

        // Server side: a fresh connection sees no flag change at all.
        try (MailSeeder s = seeder("peek")) {
            IMAPFolder f = (IMAPFolder) s.store().getFolder("INBOX");
            f.open(Folder.READ_ONLY);
            try {
                assertThat(f.getMessages()).hasSize(2);
                for (Message m : f.getMessages()) {
                    Flags flags = m.getFlags();
                    assertThat(flags.contains(Flags.Flag.SEEN)).isFalse();
                    assertThat(flags.contains(Flags.Flag.ANSWERED)).isFalse();
                    assertThat(flags.contains(Flags.Flag.FLAGGED)).isFalse();
                    assertThat(flags.contains(Flags.Flag.DELETED)).isFalse();
                    assertThat(flags.contains(Flags.Flag.DRAFT)).isFalse();
                    assertThat(flags.getUserFlags()).isEmpty();
                }
            } finally {
                f.close(false);
            }
        }
    }

    @Test
    void commandsPerSearchPageDoNotDependOnThePageSize() throws Exception {
        try (MailSeeder s = seeder("count")) {
            for (int i = 0; i < 100; i++) {
                s.appendPlain("INBOX", "m" + i, "body " + i);
            }
        }
        // Warm-up: after this the account's connection is pooled, so the logins are not part of the counts.
        ok("search_messages", args("account", account("count"), "folder", "INBOX", "page_size", 1));

        List<Integer> counts = new ArrayList<>();
        for (int pageSize : List.of(1, 100, 1, 100)) {
            long before = logSize();
            Result r = ok("search_messages",
                    args("account", account("count"), "folder", "INBOX", "page_size", pageSize));
            assertThat(list(r.structured, "messages")).hasSize(pageSize);
            List<String> commands = commandsSince(before);
            assertThat(commands).as("page_size %s", pageSize).isNotEmpty();
            counts.add(commands.size());
        }
        assertThat(Set.copyOf(counts)).as("command counts for page_size 1/100/1/100: %s", counts).hasSize(1);
    }

    /** Deletes the folder, waits so Dovecot hands out a new UIDVALIDITY (it derives it from the creation time). */
    private static void recreate(String id, String folder, int messages) throws Exception {
        try (MailSeeder s = seeder(id)) {
            String before = uidValidity(s, folder);
            s.store().getFolder(folder).delete(true);
            Thread.sleep(1100);
            for (int i = 0; i < messages; i++) {
                s.appendPlain(folder, "n" + i, "new body " + i);
            }
            assertThat(uidValidity(s, folder)).as("UIDVALIDITY after recreate").isNotEqualTo(before);
        }
    }

    private static String uidValidity(MailSeeder s, String folder) throws MessagingException {
        IMAPFolder f = (IMAPFolder) s.store().getFolder(folder);
        f.open(Folder.READ_ONLY);
        try {
            return Long.toString(f.getUIDValidity());
        } finally {
            f.close(false);
        }
    }
}
