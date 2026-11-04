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
import java.util.stream.Collectors;
import org.eclipse.angus.mail.imap.IMAPFolder;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * {@code copy_messages} against Dovecot 2.4.5 in the three capability sets of SPEC 5.8 (v2 write tests): UID COPY with
 * COPYUID handles into a non-ASCII target (UTF-8 on the full set, where Angus enables UTF8=ACCEPT itself, modified
 * UTF-7 on the profile without it), the role target, {@code same_folder}, the UIDPLUS refusal
 * and the wire trace. The server runs once (stdio, shaded jar, {@code imap.trace: true}); every scenario has its own
 * Dovecot user (own store) and account. Each wire test reads only the log bytes written during its own call.
 */
@Testcontainers
class CopyMessagesDovecotIT {

    /** A client command line of the trace: tag, command, rest. Server responses ("A1 OK ...") are filtered out. */
    private static final Pattern CLIENT_COMMAND =
            Pattern.compile(" imapmcp\\.imap\\.trace - ([A-Z]+\\d+) (?!(?:OK|NO|BAD)\\b)([A-Z][A-Z.]*\\b.*)$");

    /** "Projekte/Übersicht"; on the wire "Projekte/&ANw-bersicht" (modified UTF-7) unless UTF8=ACCEPT is enabled. */
    private static final String TARGET = "Projekte/Übersicht";
    private static final String TARGET_UTF7_TAIL = "&ANw-bersicht";

    @Container
    static final DovecotContainer FULL = new DovecotContainer(DovecotContainer.Profile.FULL);
    @Container
    static final DovecotContainer NO_MOVE = new DovecotContainer(DovecotContainer.Profile.NO_MOVE);
    @Container
    static final DovecotContainer NO_UIDPLUS = new DovecotContainer(DovecotContainer.Profile.NO_MOVE_NO_UIDPLUS);

    /** Without UTF8=ACCEPT: Angus sends no ENABLE and names travel in modified UTF-7 (SPEC 5.4, 5.8). */
    @Container
    static final DovecotContainer NO_UTF8 = new DovecotContainer(DovecotContainer.Profile.NO_UTF8_ACCEPT);

    @TempDir
    static Path dir;

    private static ServerConfig config;
    private static McpSyncClient client;
    private static final Map<String, String> ACCOUNTS = new LinkedHashMap<>();
    private static final Map<String, DovecotContainer> SERVER_OF = new LinkedHashMap<>();

    @BeforeAll
    static void start() throws Exception {
        ServerConfigWriter writer = new ServerConfigWriter(dir).trustStore(TestTrust.writeTruststore(dir))
                .extraYaml("imap:\n  trace: true\n");
        SERVER_OF.put("copy", FULL);
        SERVER_OF.put("role", FULL);
        SERVER_OF.put("same", FULL);
        SERVER_OF.put("utf7", FULL);
        SERVER_OF.put("nomove", NO_MOVE);
        SERVER_OF.put("noup", NO_UIDPLUS);
        SERVER_OF.forEach((id, c) -> writer.account(id, c.getHost(), c.imapsPort(),
                MailAccount.Security.IMPLICIT_TLS, "cp-" + id, DovecotContainer.PASSWORD));
        config = writer.write();
        List<String> cmd = JarLauncher.builder(config, "stdio").command();
        ServerParameters params = ServerParameters.builder(cmd.get(0)).args(cmd.subList(1, cmd.size()))
                .env(config.env()).build();
        client = McpClient.sync(new StdioClientTransport(params, McpJsonDefaults.getMapper()))
                .requestTimeout(Duration.ofSeconds(60)).initializationTimeout(Duration.ofSeconds(60)).build();
        client.initialize();
        for (Map<String, Object> a : list(call("list_accounts", Map.of()).structured, "accounts")) {
            ACCOUNTS.put((String) a.get("label"), (String) a.get("account"));
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
        return new Result(Boolean.TRUE.equals(r.isError()), (Map<String, Object>) r.structuredContent());
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
        return ACCOUNTS.get(id);
    }

    private static MailSeeder seeder(String id) throws MessagingException {
        return SERVER_OF.get(id).seeder("cp-" + id);
    }

    /** Appends a message and sets the given system flags on it (test-side, over the seeder connection). */
    private static void seed(MailSeeder s, String folder, String subject, Flags.Flag... flags)
            throws MessagingException {
        long uid = s.appendPlain(folder, subject, "body of " + subject);
        if (flags.length == 0) {
            return;
        }
        IMAPFolder f = (IMAPFolder) s.store().getFolder(folder);
        f.open(Folder.READ_WRITE);
        try {
            for (Flags.Flag flag : flags) {
                f.getMessageByUID(uid).setFlag(flag, true);
            }
        } finally {
            f.close(false);
        }
    }

    private static void ensureFolder(MailSeeder s, String name) throws MessagingException {
        IMAPFolder f = (IMAPFolder) s.store().getFolder(name);
        if (!f.exists()) {
            f.create(Folder.HOLDS_MESSAGES);
        }
    }

    private static String folderWithAttribute(MailSeeder seeder, String attribute) throws MessagingException {
        for (Folder f : seeder.store().getDefaultFolder().list("*")) {
            if (List.of(((IMAPFolder) f).getAttributes()).contains(attribute)) {
                return f.getFullName();
            }
        }
        throw new IllegalStateException("no folder with " + attribute);
    }

    /** The handle of the message with the given subject in {@code folder}. */
    private static String handleOf(String accountId, String folder, String subject) {
        Result r = ok("search_messages", args("account", account(accountId), "folder", folder));
        return list(r.structured, "messages").stream()
                .filter(m -> subject.equals(map(m, "untrusted").get("subject")))
                .map(m -> (String) m.get("handle")).findFirst().orElseThrow();
    }

    private static long logSize() throws IOException {
        return Files.exists(config.logFile()) ? Files.size(config.logFile()) : 0;
    }

    /** Client commands (tag stripped, e.g. {@code UID COPY 1:3 "..."}) the server sent since {@code offset}. */
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

    private static void assertNoMutation(List<String> commands) {
        assertThat(commands).noneMatch(c -> c.contains("COPY") || c.contains("MOVE") || c.contains("EXPUNGE")
                || c.contains("STORE") || c.startsWith("APPEND") || c.startsWith("CREATE")
                || c.startsWith("DELETE") || c.startsWith("RENAME"));
    }

    /** Sorted flag sets of the given UIDs, so a copy can be compared to its source without knowing the mapping. */
    private static List<String> flagSets(MailboxSnapshot.FolderState state, Iterable<Long> uids) {
        List<String> out = new ArrayList<>();
        uids.forEach(u -> out.add(state.flags().get(u).toString()));
        return out.stream().sorted().collect(Collectors.toList());
    }

    // ---- tests ---------------------------------------------------------------------------------------------

    @Test
    void copiesThreeHandlesFromTwoFoldersIntoANonAsciiTargetAndChangesNothingElse() throws Exception {
        MailboxSnapshot before;
        try (MailSeeder s = seeder("copy")) {
            seed(s, "INBOX", "a1", Flags.Flag.FLAGGED);
            seed(s, "INBOX", "a2");
            seed(s, "Quelle", "b1", Flags.Flag.SEEN);
            ensureFolder(s, TARGET);
            before = MailboxSnapshot.of(s.store(), "INBOX", "Quelle", TARGET);
        }
        List<String> handles = List.of(handleOf("copy", "INBOX", "a1"), handleOf("copy", "INBOX", "a2"),
                handleOf("copy", "Quelle", "b1"));

        long offset = logSize();
        Result r = ok("copy_messages", args("account", account("copy"), "handles", handles, "target_folder", TARGET));
        List<String> commands = commandsSince(offset);

        assertThat(r.structured).containsEntry("target_folder", TARGET);
        assertThat(map(r.structured, "summary")).containsEntry("requested", 3).containsEntry("ok", 3);
        List<Map<String, Object>> results = list(r.structured, "results");
        assertThat(results).hasSize(3);
        List<String> subjects = List.of("a1", "a2", "b1");
        for (int i = 0; i < 3; i++) {
            assertThat(results.get(i)).containsEntry("handle", handles.get(i)).containsEntry("status", "ok");
            String copy = (String) results.get(i).get("new_handle");
            assertThat(copy).isNotNull().isNotEqualTo(handles.get(i));
            Result msg = ok("get_message", args("account", account("copy"), "message", copy));
            assertThat(msg.structured).containsEntry("folder", TARGET);
            assertThat(map(msg.structured, "untrusted")).containsEntry("subject", subjects.get(i));
        }

        MailboxSnapshot after;
        try (MailSeeder s = seeder("copy")) {
            after = MailboxSnapshot.of(s.store(), "INBOX", "Quelle", TARGET);
        }
        MailboxSnapshot.Diff diff = MailboxSnapshot.diff(before, after);
        // Exactly three new UIDs in the target, nothing else differs (no \Seen change on any source message).
        assertThat(diff.added(TARGET)).hasSize(3);
        assertThat(diff.added()).containsOnlyKeys(TARGET);
        assertThat(diff.removed()).isEmpty();
        assertThat(diff.flagChanged()).isEmpty();
        assertThat(diff.uidValidityChanged()).isEmpty();
        // The copies carry the source flags: one \Flagged, one \Seen, one without.
        assertThat(flagSets(after.folder(TARGET), diff.added(TARGET)))
                .containsExactlyInAnyOrder("[\\Flagged]", "[\\Seen]", "[]");

        // The full set advertises UTF8=ACCEPT: Angus enabled it at login, so the name travels as UTF-8 (SPEC 5.4).
        assertThat(commandsSince(0)).contains("ENABLE UTF8=ACCEPT");
        assertThat(commands).filteredOn(c -> c.startsWith("UID COPY")).hasSize(2)
                .allMatch(c -> c.endsWith("\"" + TARGET + "\""));
        assertThat(commands).noneMatch(c -> c.startsWith("COPY") || c.contains("MOVE") || c.contains("EXPUNGE"));
        assertThat(commands).noneMatch(c -> c.contains("STORE"));
        assertThat(commands).filteredOn(c -> c.startsWith("SELECT") || c.startsWith("EXAMINE"))
                .noneMatch(c -> c.contains("bersicht"));
    }

    /** The same mailbox seen from a connection without ENABLE: the copy target created via UTF-8 is there, in modified UTF-7. */
    @Test
    void aTargetUsedInTheUtf8StateIsTheSameMailboxSeenWithoutEnable() throws Exception {
        try (MailSeeder s = seeder("utf7")) {
            seed(s, "INBOX", "x1");
            ensureFolder(s, TARGET);
        }
        ok("copy_messages", args("account", account("utf7"), "handles", List.of(handleOf("utf7", "INBOX", "x1")),
                "target_folder", TARGET));
        try (RawImap raw = new RawImap(FULL.getHost(), FULL.imapsPort())) {
            raw.login("cp-utf7", DovecotContainer.PASSWORD); // no ENABLE: modified UTF-7 state
            assertThat(raw.listNames()).contains("Projekte/" + TARGET_UTF7_TAIL).noneMatch(n -> n.contains("\u00dc"));
        }
    }

    /**
     * SPEC 5.4 on a server without UTF8=ACCEPT: Angus sends no ENABLE (checked over the whole wire of a server process
     * that only talks to this container), the name travels as modified UTF-7, and the verbatim name round-trips.
     */
    @Test
    void withoutUtf8AcceptNoEnableIsSentAndTheTargetTravelsAsModifiedUtf7AndRoundTrips() throws Exception {
        try (MailSeeder s = NO_UTF8.seeder("cp-strict")) {
            seed(s, "INBOX", "w1");
            ensureFolder(s, TARGET);
        }
        Path strictDir = java.nio.file.Files.createDirectories(dir.resolve("strict"));
        try (TracedServer server = new TracedServer(NO_UTF8, strictDir, "cp-strict")) {
            Map<String, Object> search = server.ok("search_messages", "cp-strict", "folder", "INBOX");
            String handle = (String) list(search, "messages").get(0).get("handle");
            long offset = server.logSize();
            Map<String, Object> r = server.ok("copy_messages", "cp-strict", "handles", List.of(handle),
                    "target_folder", TARGET);
            assertThat(server.commandsSince(0)).as("whole wire of this server").isNotEmpty()
                    .noneMatch(c -> c.startsWith("ENABLE"));
            assertThat(server.commandsSince(offset)).filteredOn(c -> c.startsWith("UID COPY")).hasSize(1)
                    .allMatch(c -> c.endsWith("Projekte/" + TARGET_UTF7_TAIL)); // an atom: Angus quotes only when needed
            assertThat(r).containsEntry("target_folder", TARGET);
            String copy = (String) list(r, "results").get(0).get("new_handle");
            Map<String, Object> msg = server.ok("get_message", "cp-strict", "message", copy);
            assertThat(msg).containsEntry("folder", TARGET);
        }
    }

    @Test
    void roleTargetArchiveResolvesToTheSpecialUseFolder() throws Exception {
        String archive;
        MailboxSnapshot before;
        try (MailSeeder s = seeder("role")) {
            archive = folderWithAttribute(s, "\\Archive");
            seed(s, "INBOX", "r1");
            before = MailboxSnapshot.of(s.store(), "INBOX", archive);
        }
        String handle = handleOf("role", "INBOX", "r1");
        Result r = ok("copy_messages",
                args("account", account("role"), "handles", List.of(handle), "target_folder_role", "archive"));
        assertThat(r.structured).containsEntry("target_folder", archive);
        String copy = (String) list(r.structured, "results").get(0).get("new_handle");
        assertThat(ok("get_message", args("account", account("role"), "message", copy)).structured)
                .containsEntry("folder", archive);
        try (MailSeeder s = seeder("role")) {
            MailboxSnapshot.Diff diff = MailboxSnapshot.diff(before, MailboxSnapshot.of(s.store(), "INBOX", archive));
            assertThat(diff.added(archive)).hasSize(1);
            assertThat(diff.added()).containsOnlyKeys(archive);
            assertThat(diff.removed()).isEmpty();
            assertThat(diff.flagChanged()).isEmpty();
        }
    }

    @Test
    void copyToTheSameFolderIsRefusedBeforeAnyMutatingCommand() throws Exception {
        String archive;
        MailboxSnapshot before;
        try (MailSeeder s = seeder("same")) {
            archive = folderWithAttribute(s, "\\Archive");
            seed(s, "INBOX", "s1");
            seed(s, archive, "s2");
            before = MailboxSnapshot.of(s.store(), "INBOX", archive);
        }
        String inbox = handleOf("same", "INBOX", "s1");
        String inArchive = handleOf("same", archive, "s2");

        long offset = logSize();
        Result verbatim = call("copy_messages",
                args("account", account("same"), "handles", List.of(inbox), "target_folder", "INBOX"));
        assertThat(verbatim.error()).isTrue();
        assertThat(verbatim.errorCode()).isEqualTo("INVALID_ARGUMENT");
        assertThat(verbatim.detail("reason")).isEqualTo("same_folder");
        assertThat(verbatim.detail("field")).isEqualTo("target_folder");

        Result role = call("copy_messages",
                args("account", account("same"), "handles", List.of(inArchive), "target_folder_role", "archive"));
        assertThat(role.error()).isTrue();
        assertThat(role.errorCode()).isEqualTo("INVALID_ARGUMENT");
        assertThat(role.detail("reason")).isEqualTo("same_folder");
        assertThat(role.detail("field")).isEqualTo("target_folder_role");

        List<String> commands = commandsSince(offset);
        assertNoMutation(commands);
        try (MailSeeder s = seeder("same")) {
            assertThat(MailboxSnapshot.diff(before, MailboxSnapshot.of(s.store(), "INBOX", archive)).isEmpty())
                    .isTrue();
        }
    }

    @Test
    void withoutUidplusTheCallIsUnsupportedAndNoFolderIsSelected() throws Exception {
        assertCapabilities("noup", false);
        MailboxSnapshot before;
        try (MailSeeder s = seeder("noup")) {
            seed(s, "INBOX", "u1");
            ensureFolder(s, "Ziel");
            before = MailboxSnapshot.of(s.store(), "INBOX", "Ziel");
        }
        String handle = handleOf("noup", "INBOX", "u1");

        long offset = logSize();
        Result r = call("copy_messages",
                args("account", account("noup"), "handles", List.of(handle), "target_folder", "Ziel"));
        List<String> commands = commandsSince(offset);

        assertThat(r.error()).isTrue();
        assertThat(r.errorCode()).isEqualTo("UNSUPPORTED");
        assertThat(r.detail("capability")).isEqualTo("UIDPLUS");
        assertThat(commands).noneMatch(c -> c.startsWith("SELECT") || c.startsWith("EXAMINE"));
        assertNoMutation(commands);
        try (MailSeeder s = seeder("noup")) {
            assertThat(MailboxSnapshot.diff(before, MailboxSnapshot.of(s.store(), "INBOX", "Ziel")).isEmpty())
                    .isTrue();
        }
    }

    @Test
    void withoutMoveCopyStillWorksBecauseUidplusIsPresent() throws Exception {
        assertCapabilities("nomove", true);
        MailboxSnapshot before;
        try (MailSeeder s = seeder("nomove")) {
            seed(s, "INBOX", "n1", Flags.Flag.SEEN);
            ensureFolder(s, "Ziel");
            before = MailboxSnapshot.of(s.store(), "INBOX", "Ziel");
        }
        String handle = handleOf("nomove", "INBOX", "n1");
        long offset = logSize();
        Result r = ok("copy_messages",
                args("account", account("nomove"), "handles", List.of(handle), "target_folder", "Ziel"));
        List<String> commands = commandsSince(offset);

        assertThat(map(r.structured, "summary")).containsEntry("ok", 1);
        String copy = (String) list(r.structured, "results").get(0).get("new_handle");
        assertThat(map(ok("get_message", args("account", account("nomove"), "message", copy)).structured,
                "untrusted")).containsEntry("subject", "n1");
        try (MailSeeder s = seeder("nomove")) {
            MailboxSnapshot.Diff diff = MailboxSnapshot.diff(before, MailboxSnapshot.of(s.store(), "INBOX", "Ziel"));
            assertThat(diff.added()).containsOnlyKeys("Ziel");
            assertThat(diff.added("Ziel")).hasSize(1);
            assertThat(diff.removed()).isEmpty();
            assertThat(diff.flagChanged()).isEmpty();
        }
        assertThat(commands).anyMatch(c -> c.startsWith("UID COPY"));
        assertThat(commands).noneMatch(c -> c.startsWith("COPY") || c.contains("MOVE") || c.contains("EXPUNGE")
                || c.contains("STORE"));
    }

    @Test
    void copyMessagesIsAdvertisedAsAdditiveAndNotIdempotent() {
        McpSchema.Tool tool = client.listTools().tools().stream().filter(t -> t.name().equals("copy_messages"))
                .findFirst().orElseThrow();
        assertThat(tool.annotations().destructiveHint()).isFalse();
        assertThat(tool.annotations().idempotentHint()).isFalse();
        assertThat(tool.annotations().readOnlyHint()).isFalse();
    }

    @SuppressWarnings("unchecked")
    private static void assertCapabilities(String id, boolean uidplus) {
        Map<String, Object> caps = list(ok("list_accounts", args()).structured, "accounts").stream()
                .filter(a -> account(id).equals(a.get("account"))).findFirst().map(a -> map(a, "capabilities"))
                .orElseThrow();
        assertThat(caps).containsEntry("move", false).containsEntry("uidplus", uidplus);
    }
}
