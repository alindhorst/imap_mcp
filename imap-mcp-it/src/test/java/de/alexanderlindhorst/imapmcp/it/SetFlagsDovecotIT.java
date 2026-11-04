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
import java.io.ByteArrayOutputStream;
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
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.eclipse.angus.mail.imap.IMAPFolder;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * {@code set_flags} against a real Dovecot 2.4.5, FULL capability profile (SPEC 4 "v2 write contract", 5.4, 5.8 v2
 * write tests): results, server-side flag changes seen by an independent connection, keywords (PERMANENTFLAGS
 * {@code \*}), clearing {@code \Deleted} without any expunge, idempotence, the wire trace, a changed UIDVALIDITY and
 * the read-only account gate.
 *
 * <p>The tools are driven through the stdio client against the shaded jar with {@code imap.trace: true}, as in
 * {@link DovecotFidelityIT}; each test reads only the trace bytes written during its own calls and uses its own
 * Dovecot user (own store) and configured account.
 */
@Testcontainers
class SetFlagsDovecotIT {

    private static final Pattern CLIENT_COMMAND =
            Pattern.compile(" imapmcp\\.imap\\.trace - ([A-Z]+\\d+) (?!(?:OK|NO|BAD)\\b)([A-Z][A-Z.]*\\b.*)$");
    private static final Pattern UID_STORE = Pattern.compile("UID STORE [0-9,:]+ [+-]FLAGS\\.SILENT \\([^)]+\\)");
    private static final List<String> WRITE_TOOLS =
            List.of("set_flags", "move_messages", "copy_messages", "delete_messages", "create_folder");
    private static final JsonMapper JSON = JsonMapper.builder().build();

    @Container
    static final DovecotContainer DOVECOT = new DovecotContainer(DovecotContainer.Profile.FULL);

    @TempDir
    static Path dir;

    private static ServerConfig config;
    private static McpSyncClient client;
    private static final Map<String, String> ACCOUNT_HANDLES = new LinkedHashMap<>();

    @BeforeAll
    static void start() throws Exception {
        // Default config: no readOnly on the accounts that write, one readOnly account for the gate test.
        ServerConfigWriter writer = new ServerConfigWriter(Files.createDirectories(dir.resolve("main")))
                .trustStore(TestTrust.writeTruststore(dir)).extraYaml("imap:\n  trace: true\n");
        for (String id : List.of("basic", "keywords", "deleted", "idem", "stale", "wire", "ro")) {
            writer.account(id, DOVECOT.getHost(), DOVECOT.imapsPort(), MailAccount.Security.IMPLICIT_TLS, "sf-" + id,
                    DovecotContainer.PASSWORD);
        }
        config = writer.readOnly("ro").write();
        client = newClient(config);
        for (Map<String, Object> a : list(call("list_accounts", Map.of()).structured, "accounts")) {
            ACCOUNT_HANDLES.put((String) a.get("label"), (String) a.get("account"));
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

    private static McpSyncClient newClient(ServerConfig cfg) {
        List<String> cmd = JarLauncher.builder(cfg, "stdio").command();
        ServerParameters params = ServerParameters.builder(cmd.get(0)).args(cmd.subList(1, cmd.size()))
                .env(cfg.env()).build();
        McpSyncClient c = McpClient.sync(new StdioClientTransport(params, McpJsonDefaults.getMapper()))
                .requestTimeout(Duration.ofSeconds(60)).initializationTimeout(Duration.ofSeconds(60)).build();
        c.initialize();
        return c;
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
        return ACCOUNT_HANDLES.get(id);
    }

    private static MailSeeder seeder(String id) throws MessagingException {
        return DOVECOT.seeder("sf-" + id);
    }

    /** Seeds {@code count} messages "m0".. in the folder; returns their UIDs in order. */
    private static List<Long> seed(String id, String folder, int count) throws MessagingException {
        List<Long> uids = new ArrayList<>();
        try (MailSeeder s = seeder(id)) {
            for (int i = 0; i < count; i++) {
                uids.add(s.appendPlain(folder, "m" + i, "body " + i));
            }
        }
        return uids;
    }

    /** Test fixture only, on a separate connection: adds flags to one UID. */
    private static void addFlags(String id, String folder, long uid, Flags flags) throws MessagingException {
        try (MailSeeder s = seeder(id)) {
            IMAPFolder f = (IMAPFolder) s.store().getFolder(folder);
            f.open(Folder.READ_WRITE);
            try {
                Message m = f.getMessageByUID(uid);
                m.setFlags(flags, true);
            } finally {
                f.close(false);
            }
        }
    }

    private static MailboxSnapshot snapshot(String id, String... folders) throws MessagingException {
        try (MailSeeder s = seeder(id)) {
            return MailboxSnapshot.of(s.store(), folders);
        }
    }

    /** Handles of a folder keyed by subject ("m0", ...). */
    private static Map<String, String> handles(String id, String folder) {
        Map<String, String> out = new LinkedHashMap<>();
        for (Map<String, Object> m : list(ok("search_messages",
                args("account", account(id), "folder", folder, "page_size", 100)).structured, "messages")) {
            out.put((String) map(m, "untrusted").get("subject"), (String) m.get("handle"));
        }
        return out;
    }

    private static List<String> statuses(Result r) {
        return list(r.structured, "results").stream().map(i -> (String) i.get("status")).toList();
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

    /** SPEC 5.4/5.8: UID STORE +/-FLAGS.SILENT only; no plain STORE, FLAGS replace or EXPUNGE; no CLOSE on RW. */
    private static void assertWriteWire(List<String> commands) {
        assertThat(commands).anyMatch(c -> c.startsWith("SELECT "));
        List<String> stores = commands.stream().filter(c -> c.contains("STORE")).toList();
        assertThat(stores).isNotEmpty().allMatch(c -> UID_STORE.matcher(c).matches());
        assertThat(commands).noneMatch(c -> c.startsWith("STORE") || c.matches(".*\\bFLAGS(?!\\.SILENT)\\b \\(.*"));
        assertThat(commands).noneMatch(c -> c.contains("EXPUNGE"));
        assertThat(commands).noneMatch(c -> c.equals("CLOSE"));
        assertThat(commands).anyMatch(c -> c.equals("UNSELECT"));
    }

    // ---- tests ---------------------------------------------------------------------------------------------

    @Test
    void toolsListHasSetFlagsWithItsAnnotationsByDefault() {
        McpSchema.Tool tool = client.listTools().tools().stream().filter(t -> t.name().equals("set_flags"))
                .findFirst().orElseThrow();
        assertThat(tool.annotations().readOnlyHint()).isFalse();
        assertThat(tool.annotations().destructiveHint()).isTrue();
        assertThat(tool.annotations().idempotentHint()).isTrue();
        assertThat(client.listTools().tools().stream().map(McpSchema.Tool::name)).containsAll(WRITE_TOOLS);
    }

    @Test
    void withEveryAccountReadOnlyNoWriteToolIsListed() throws Exception {
        Path root = Files.createDirectories(dir.resolve("allro"));
        ServerConfig cfg = new ServerConfigWriter(root)
                .account("a", DOVECOT.getHost(), DOVECOT.imapsPort(), MailAccount.Security.IMPLICIT_TLS, "sf-allro",
                        DovecotContainer.PASSWORD)
                .account("b", DOVECOT.getHost(), DOVECOT.imapsPort(), MailAccount.Security.IMPLICIT_TLS, "sf-allro",
                        DovecotContainer.PASSWORD)
                .readOnly("a").readOnly("b").trustStore(TestTrust.writeTruststore(root)).write();
        try (McpSyncClient ro = newClient(cfg)) {
            List<String> names = ro.listTools().tools().stream().map(McpSchema.Tool::name).toList();
            assertThat(names).contains("search_messages").doesNotContainAnyElementsOf(WRITE_TOOLS);
        }
    }

    @Test
    void flagsAreSetAndRemovedAndOnlyTheAddressedFlagsChangeOnTheServer() throws Exception {
        List<Long> uids = seed("basic", "INBOX", 4);
        addFlags("basic", "INBOX", uids.get(1), new Flags(Flags.Flag.ANSWERED));
        Map<String, String> h = handles("basic", "INBOX");
        MailboxSnapshot before = snapshot("basic", "INBOX");

        long mark = logSize();
        Result both = ok("set_flags", args("account", account("basic"), "handles", List.of(h.get("m0"), h.get("m1")),
                "seen", true, "flagged", true));
        Result unanswer = ok("set_flags",
                args("account", account("basic"), "handles", List.of(h.get("m1")), "answered", false));
        List<String> commands = commandsSince(mark);

        assertThat(statuses(both)).containsExactly("ok", "ok");
        assertThat(statuses(unanswer)).containsExactly("ok");
        assertThat(map(both.structured, "summary")).containsEntry("requested", 2).containsEntry("ok", 2);
        MailboxSnapshot after = snapshot("basic", "INBOX");
        MailboxSnapshot.Diff diff = MailboxSnapshot.diff(before, after);
        assertThat(diff.flagChanged("INBOX")).containsExactlyInAnyOrder(uids.get(0), uids.get(1));
        assertThat(diff.added()).isEmpty();
        assertThat(diff.removed()).isEmpty();
        assertThat(diff.uidValidityChanged()).isEmpty();
        assertThat(after.folder("INBOX").flags().get(uids.get(0))).containsExactlyInAnyOrder("\\Seen", "\\Flagged");
        assertThat(after.folder("INBOX").flags().get(uids.get(1))).containsExactlyInAnyOrder("\\Seen", "\\Flagged");
        assertThat(after.folder("INBOX").flags().get(uids.get(2))).isEmpty();
        assertThat(after.folder("INBOX").flags().get(uids.get(3))).isEmpty();

        assertWriteWire(commands);
        assertThat(commands).anyMatch(c -> c.matches("UID STORE \\S+ \\+FLAGS\\.SILENT \\(.*\\)"));
        assertThat(commands).anyMatch(c -> c.matches("UID STORE \\S+ -FLAGS\\.SILENT \\(.*\\)"));
    }

    @Test
    void aKeywordIsAddedSearchableAndRemovedWherePermanentFlagsAllowsIt() throws Exception {
        List<Long> uids = seed("keywords", "INBOX", 3);
        Map<String, String> h = handles("keywords", "INBOX");
        MailboxSnapshot before = snapshot("keywords", "INBOX");

        Result add = ok("set_flags", args("account", account("keywords"), "handles", List.of(h.get("m1")),
                "add_keywords", List.of("$Project1")));
        assertThat(statuses(add)).containsExactly("ok");
        assertThat(MailboxSnapshot.diff(before, snapshot("keywords", "INBOX")).flagChanged("INBOX"))
                .containsExactly(uids.get(1));
        Result found = ok("search_messages",
                args("account", account("keywords"), "folder", "INBOX", "keywords", List.of("$Project1")));
        assertThat(list(found.structured, "messages")).hasSize(1);
        assertThat(list(found.structured, "messages").get(0)).containsEntry("handle", h.get("m1"));
        @SuppressWarnings("unchecked")
        List<String> flags = (List<String>) list(found.structured, "messages").get(0).get("flags");
        assertThat(flags).contains("$Project1");

        Result remove = ok("set_flags", args("account", account("keywords"), "handles", List.of(h.get("m1")),
                "remove_keywords", List.of("$Project1")));
        assertThat(statuses(remove)).containsExactly("ok");
        assertThat(MailboxSnapshot.diff(before, snapshot("keywords", "INBOX")).isEmpty()).isTrue();
        assertThat(list(ok("search_messages", args("account", account("keywords"), "folder", "INBOX", "keywords",
                List.of("$Project1"))).structured, "messages")).isEmpty();
    }

    @Test
    void deletedFalseClearsTheFlagAndNothingIsExpunged() throws Exception {
        List<Long> uids = seed("deleted", "INBOX", 3);
        addFlags("deleted", "INBOX", uids.get(0), new Flags(Flags.Flag.DELETED));
        addFlags("deleted", "INBOX", uids.get(1), new Flags(Flags.Flag.DELETED));
        Map<String, String> h = handles("deleted", "INBOX");
        MailboxSnapshot before = snapshot("deleted", "INBOX");

        long mark = logSize();
        Result r = ok("set_flags",
                args("account", account("deleted"), "handles", List.of(h.get("m0")), "deleted", false));
        List<String> commands = commandsSince(mark);

        assertThat(statuses(r)).containsExactly("ok");
        MailboxSnapshot after = snapshot("deleted", "INBOX");
        assertThat(MailboxSnapshot.diff(before, after).flagChanged("INBOX")).containsExactly(uids.get(0));
        assertThat(after.folder("INBOX").flags().get(uids.get(0))).doesNotContain("\\Deleted");
        assertThat(after.folder("INBOX").flags().get(uids.get(1))).contains("\\Deleted");
        assertThat(after.folder("INBOX").flags()).hasSize(3);
        assertWriteWire(commands);
    }

    @Test
    void repeatingTheCallIsOkAndLeavesTheStateUnchanged() throws Exception {
        seed("idem", "INBOX", 2);
        Map<String, String> h = handles("idem", "INBOX");
        Map<String, Object> a = args("account", account("idem"), "handles", List.of(h.get("m0")), "seen", true,
                "add_keywords", List.of("$Once"));
        assertThat(statuses(ok("set_flags", a))).containsExactly("ok");
        MailboxSnapshot once = snapshot("idem", "INBOX");

        assertThat(statuses(ok("set_flags", a))).containsExactly("ok");
        assertThat(MailboxSnapshot.diff(once, snapshot("idem", "INBOX")).isEmpty()).isTrue();
    }

    @Test
    void readToolsInTheSameSessionStillExamineAndPeekAndChangeNoFlag() throws Exception {
        List<Long> uids = seed("wire", "INBOX", 2);
        Map<String, String> h = handles("wire", "INBOX");

        long mark = logSize();
        ok("set_flags", args("account", account("wire"), "handles", List.of(h.get("m0")), "flagged", true));
        long afterWrite = logSize();
        ok("search_messages", args("account", account("wire"), "folder", "INBOX"));
        ok("get_message", args("account", account("wire"), "message", h.get("m1")));
        List<String> writeCommands = commandsSince(mark);
        List<String> readCommands = commandsSince(afterWrite);

        assertWriteWire(writeCommands.subList(0, writeCommands.size() - readCommands.size()));
        assertThat(readCommands).anyMatch(c -> c.startsWith("EXAMINE "));
        assertThat(readCommands).noneMatch(c -> c.startsWith("SELECT"));
        assertThat(readCommands).noneMatch(c -> c.contains("STORE") || c.contains("EXPUNGE"));
        List<String> fetches = readCommands.stream().filter(c -> c.contains("FETCH")).toList();
        assertThat(fetches).anyMatch(c -> c.contains("BODY.PEEK["));
        assertThat(fetches).noneMatch(c -> c.contains("BODY[") || c.contains("RFC822.TEXT"));
        // The read of m1 left it unread; only m0 changed.
        MailboxSnapshot after = snapshot("wire", "INBOX");
        assertThat(after.folder("INBOX").flags().get(uids.get(0))).containsExactly("\\Flagged");
        assertThat(after.folder("INBOX").flags().get(uids.get(1))).isEmpty();
    }

    @Test
    void aHandleWithAChangedUidvalidityIsStaleReference() throws Exception {
        seed("stale", "Work", 2);
        Map<String, String> h = handles("stale", "Work");
        try (MailSeeder s = seeder("stale")) {
            long before = uidValidity(s, "Work");
            s.store().getFolder("Work").delete(true);
            Thread.sleep(1100); // Dovecot derives UIDVALIDITY from the creation time
            s.appendPlain("Work", "n0", "new body");
            assertThat(uidValidity(s, "Work")).isNotEqualTo(before);
        }
        long mark = logSize();
        Result r = ok("set_flags", args("account", account("stale"), "handles", List.of(h.get("m0")), "seen", true));
        assertThat(statuses(r)).containsExactly("stale_reference");
        assertThat(list(r.structured, "results").get(0)).containsEntry("reason", "uidvalidity_changed");
        assertThat(map(r.structured, "summary")).containsEntry("stale_reference", 1).containsEntry("ok", 0);
        assertThat(commandsSince(mark)).noneMatch(c -> c.contains("STORE"));
        try (MailSeeder s = seeder("stale")) {
            assertThat(MailboxSnapshot.of(s.store(), "Work").folder("Work").flags().values())
                    .allMatch(Set::isEmpty);
        }
    }

    @Test
    void aReadOnlyAccountIsRefusedBeforeAnyImapIo() throws Exception {
        long uid;
        try (MailSeeder s = seeder("ro")) {
            uid = s.appendPlain("INBOX", "m0", "body");
        }
        Map<String, String> h = handles("ro", "INBOX");
        MailboxSnapshot before = snapshot("ro", "INBOX");

        long mark = logSize();
        Result r = call("set_flags", args("account", account("ro"), "handles", List.of(h.get("m0")), "seen", true));
        List<String> commands = commandsSince(mark);

        assertThat(r.error()).isTrue();
        assertThat(r.errorCode()).isEqualTo("PERMISSION_DENIED");
        assertThat(r.detail("reason")).isEqualTo("account_read_only");
        assertThat(commands).as("no SELECT, no I/O at all").isEmpty();
        assertThat(MailboxSnapshot.diff(before, snapshot("ro", "INBOX")).isEmpty()).isTrue();
        assertThat(uid).isPositive();
    }

    @Test
    void everyStdoutLineIsJsonRpcWhileSettingFlags() throws Exception {
        Path root = Files.createDirectories(dir.resolve("hygiene"));
        try (MailSeeder s = seeder("hygiene")) {
            s.appendPlain("INBOX", "m0", "body");
        }
        ServerConfig cfg = new ServerConfigWriter(root)
                .account("work", DOVECOT.getHost(), DOVECOT.imapsPort(), MailAccount.Security.IMPLICIT_TLS,
                        "sf-hygiene", DovecotContainer.PASSWORD)
                .trustStore(TestTrust.writeTruststore(root)).extraYaml("imap:\n  trace: true\n").write();
        try (RunningServer server = JarLauncher.start(cfg, "stdio")) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            Thread pump = Thread.ofVirtual().start(() -> {
                try {
                    server.stdout().transferTo(out);
                } catch (IOException ignored) {
                    // process gone
                }
            });
            server.send("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":{\"protocolVersion\":"
                    + "\"2025-11-25\",\"capabilities\":{},\"clientInfo\":{\"name\":\"it\",\"version\":\"1\"}}}");
            await(out, 1);
            server.send("{\"jsonrpc\":\"2.0\",\"method\":\"notifications/initialized\"}");
            JsonNode accounts = await(out, rpc(server, 2, "list_accounts", "{}")).path("result")
                    .path("structuredContent");
            String work = accounts.path("accounts").path(0).path("account").asString();
            JsonNode found = await(out, rpc(server, 3, "search_messages",
                    "{\"account\":\"" + work + "\",\"folder\":\"INBOX\"}")).path("result").path("structuredContent");
            String handle = found.path("messages").path(0).path("handle").asString();
            JsonNode set = await(out, rpc(server, 4, "set_flags", "{\"account\":\"" + work + "\",\"handles\":[\""
                    + handle + "\"],\"seen\":true,\"add_keywords\":[\"$Hyg\"]}"));
            assertThat(set.path("result").path("structuredContent").path("results").path(0).path("status").asString())
                    .isEqualTo("ok");
            server.closeStdin();
            assertThat(server.waitFor(10)).isTrue();
            pump.join(Duration.ofSeconds(5));
            String stdout = out.toString(StandardCharsets.UTF_8);
            assertThat(stdout).isNotEmpty().endsWith("\n");
            for (String line : stdout.substring(0, stdout.length() - 1).split("\n", -1)) {
                JsonNode n = JSON.readTree(line);
                assertThat(n.path("jsonrpc").asString()).isEqualTo("2.0");
                assertThat((n.has("id") && (n.has("result") || n.has("error"))) || n.has("method")).isTrue();
            }
        }
    }

    private static int rpc(RunningServer server, int id, String tool, String arguments) throws IOException {
        server.send("{\"jsonrpc\":\"2.0\",\"id\":" + id + ",\"method\":\"tools/call\",\"params\":{\"name\":\""
                + tool + "\",\"arguments\":" + arguments + "}}");
        return id;
    }

    private static JsonNode await(ByteArrayOutputStream out, int id) throws Exception {
        long deadline = System.nanoTime() + 60_000_000_000L;
        while (System.nanoTime() < deadline) {
            String text = out.toString(StandardCharsets.UTF_8);
            int end = text.lastIndexOf('\n');
            if (end >= 0) {
                for (String line : text.substring(0, end).split("\n")) {
                    JsonNode n = JSON.readTree(line);
                    if (n.has("id") && n.path("id").asLong(-1) == id && (n.has("result") || n.has("error"))) {
                        return n;
                    }
                }
            }
            Thread.sleep(25);
        }
        throw new AssertionError("no response to request " + id + " within 60 s");
    }

    private static long uidValidity(MailSeeder s, String folder) throws MessagingException {
        IMAPFolder f = (IMAPFolder) s.store().getFolder(folder);
        f.open(Folder.READ_ONLY);
        try {
            return f.getUIDValidity();
        } finally {
            f.close(false);
        }
    }
}
