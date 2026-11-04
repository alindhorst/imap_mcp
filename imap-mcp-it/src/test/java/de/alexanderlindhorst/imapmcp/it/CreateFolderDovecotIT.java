package de.alexanderlindhorst.imapmcp.it;

import static org.assertj.core.api.Assertions.assertThat;

import de.alexanderlindhorst.imapmcp.core.account.MailAccount;
import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.ServerParameters;
import io.modelcontextprotocol.client.transport.StdioClientTransport;
import io.modelcontextprotocol.json.McpJsonDefaults;
import io.modelcontextprotocol.spec.McpSchema;
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
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * {@code create_folder} against a real Dovecot 2.4.5, FULL profile (SPEC 4 "v2 write contract", 5.4, 5.8): hierarchy and
 * delimiter, subscription, idempotence, modified UTF-7 on the wire, the delimiter and missing-parent errors, INBOX, the
 * read-only account and the set of IMAP commands the tool sends.
 *
 * <p>The tool is driven through the stdio client against the shaded jar with {@code imap.trace: true} (logger
 * {@code imapmcp.imap.trace}, the only place folder names may appear). Every scenario has its own Dovecot user and
 * configured account, all in one server process; one account is {@code readOnly: true} next to the writable ones, so the
 * write tools stay registered.
 */
@Testcontainers
class CreateFolderDovecotIT {

    private static final Pattern CLIENT_COMMAND =
            Pattern.compile(" imapmcp\\.imap\\.trace - ([A-Z]+\\d+) (?!(?:OK|NO|BAD)\\b)([A-Z][A-Z.]*\\b.*)$");
    private static final String TRACE_LOGGER = " imapmcp.imap.trace - ";
    /** Appears in a folder name only; must never reach a log line outside the opt-in trace. */
    private static final String MARKER = "Zq7Marker";

    @Container
    static final DovecotContainer DOVECOT = new DovecotContainer();

    @TempDir
    static Path dir;

    private static ServerConfig config;
    private static McpSyncClient client;
    private static final Map<String, String> accountHandles = new LinkedHashMap<>();
    private static String delimiter;

    @BeforeAll
    static void start() throws Exception {
        ServerConfigWriter writer = new ServerConfigWriter(dir).trustStore(TestTrust.writeTruststore(dir))
                .extraYaml("imap:\n  trace: true\n");
        for (String id : List.of("basic", "nested", "errors", "inbox", "wire", "ro")) {
            writer.account(id, DOVECOT.getHost(), DOVECOT.imapsPort(), MailAccount.Security.IMPLICIT_TLS,
                    "cf-" + id, DovecotContainer.PASSWORD);
        }
        config = writer.readOnly("ro").write();
        List<String> cmd = JarLauncher.builder(config, "stdio").command();
        ServerParameters params = ServerParameters.builder(cmd.get(0)).args(cmd.subList(1, cmd.size()))
                .env(config.env()).build();
        client = McpClient.sync(new StdioClientTransport(params, McpJsonDefaults.getMapper()))
                .requestTimeout(Duration.ofSeconds(60)).initializationTimeout(Duration.ofSeconds(60)).build();
        client.initialize();
        for (Map<String, Object> a : list(call("list_accounts", Map.of()).structured, "accounts")) {
            accountHandles.put((String) a.get("label"), (String) a.get("account"));
        }
        try (MailSeeder s = seeder("basic")) {
            delimiter = String.valueOf(s.store().getDefaultFolder().getSeparator());
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
        return accountHandles.get(id);
    }

    private static MailSeeder seeder(String id) throws MessagingException {
        return DOVECOT.seeder("cf-" + id);
    }

    private static List<String> folders(String id) {
        return list(ok("list_folders", args("account", account(id))).structured, "folders").stream()
                .map(f -> (String) f.get("folder")).toList();
    }

    private static long logSize() throws IOException {
        return Files.exists(config.logFile()) ? Files.size(config.logFile()) : 0;
    }

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

    private static boolean verb(String command, String verb) {
        return command.equals(verb) || command.startsWith(verb + " ");
    }

    // ---- tests ---------------------------------------------------------------------------------------------

    @Test
    void createsAndSubscribesATopLevelFolderAndListFoldersShowsIt() throws Exception {
        Result r = ok("create_folder", args("account", account("basic"), "name", "Projekte"));
        assertThat(r.structured).containsEntry("folder", "Projekte").containsEntry("created", true)
                .containsEntry("subscribed", true).containsEntry("delimiter", delimiter)
                .containsEntry("account", account("basic"));
        assertThat(r.structured.get("parent")).isNull();
        assertThat(folders("basic")).contains("Projekte");
        try (MailSeeder s = seeder("basic")) {
            assertThat(s.store().getFolder("Projekte").isSubscribed()).isTrue();
        }
    }

    @Test
    void aRepeatIsCreatedFalseSubscribedTrueAndSendsNoCreate() throws Exception {
        ok("create_folder", args("account", account("wire"), "name", "Wiederholt"));
        long before = logSize();
        Result r = ok("create_folder", args("account", account("wire"), "name", "Wiederholt"));
        assertThat(r.structured).containsEntry("folder", "Wiederholt").containsEntry("created", false)
                .containsEntry("subscribed", true);
        List<String> commands = commandsSince(before);
        assertThat(commands).anyMatch(c -> verb(c, "SUBSCRIBE")).noneMatch(c -> verb(c, "CREATE"));
    }

    @Test
    void aNestedNonAsciiFolderIsSentAsModifiedUtf7AndListedVerbatim() throws Exception {
        ok("create_folder", args("account", account("nested"), "name", "Projekte"));
        long before = logSize();
        Result r = ok("create_folder",
                args("account", account("nested"), "parent", "Projekte", "name", "Übersicht 2026"));
        String full = "Projekte" + delimiter + "Übersicht 2026";
        assertThat(r.structured).containsEntry("folder", full).containsEntry("parent", "Projekte")
                .containsEntry("delimiter", delimiter).containsEntry("created", true)
                .containsEntry("subscribed", true);
        List<String> commands = commandsSince(before);
        assertThat(commands).anyMatch(c -> verb(c, "CREATE") && c.contains("&ANw-bersicht 2026"))
                .noneMatch(c -> c.contains("Ü"));
        assertThat(folders("nested")).contains(full);
    }

    @Test
    void aNameContainingTheDelimiterAndAMissingParentAreRejected() {
        Result bad = call("create_folder",
                args("account", account("errors"), "name", "A" + delimiter + "B"));
        assertThat(bad.error()).isTrue();
        assertThat(bad.errorCode()).isEqualTo("INVALID_ARGUMENT");
        assertThat(bad.detail("reason")).isEqualTo("contains_delimiter");

        Result missing = call("create_folder",
                args("account", account("errors"), "parent", "Nope" + MARKER, "name", "X"));
        assertThat(missing.error()).isTrue();
        assertThat(missing.errorCode()).isEqualTo("FOLDER_NOT_FOUND");
        assertThat(folders("errors")).noneMatch(f -> f.contains(MARKER));
    }

    @Test
    void inboxWithoutParentIsCreatedFalse() {
        Result r = ok("create_folder", args("account", account("inbox"), "name", "INBOX"));
        assertThat(r.structured).containsEntry("folder", "INBOX").containsEntry("created", false);
    }

    @Test
    void aReadOnlyAccountIsPermissionDeniedWithoutAnyCreateOnTheWire() throws Exception {
        long before = logSize();
        Result r = call("create_folder", args("account", account("ro"), "name", "Nein" + MARKER));
        assertThat(r.error()).isTrue();
        assertThat(r.errorCode()).isEqualTo("PERMISSION_DENIED");
        assertThat(r.detail("reason")).isEqualTo("account_read_only");
        assertThat(commandsSince(before)).noneMatch(c -> verb(c, "CREATE"));
        assertThat(folders("ro")).noneMatch(f -> f.contains(MARKER));
    }

    @Test
    void theToolSendsOnlyListCreateAndSubscribe() throws Exception {
        // New connection: the lease may add login-time commands; compare the tool's own commands.
        long before = logSize();
        ok("create_folder", args("account", account("wire"), "name", "Verb" + MARKER));
        ok("create_folder", args("account", account("wire"), "parent", "Verb" + MARKER, "name", "Kind"));
        List<String> commands = commandsSince(before);
        assertThat(commands).anyMatch(c -> verb(c, "LIST")).anyMatch(c -> verb(c, "CREATE"))
                .anyMatch(c -> verb(c, "SUBSCRIBE"));
        assertThat(commands).noneMatch(c -> verb(c, "SELECT") || verb(c, "EXAMINE") || verb(c, "DELETE")
                || verb(c, "RENAME") || verb(c, "UNSUBSCRIBE") || verb(c, "APPEND") || verb(c, "STORE")
                || verb(c, "UID") || verb(c, "COPY") || verb(c, "EXPUNGE"));
    }

    @Test
    void toolsListAnnotatesCreateFolderNonDestructiveAndIdempotent() {
        McpSchema.Tool t = client.listTools().tools().stream().filter(x -> x.name().equals("create_folder"))
                .findFirst().orElseThrow();
        assertThat(t.annotations().destructiveHint()).isFalse();
        assertThat(t.annotations().idempotentHint()).isTrue();
        assertThat(t.annotations().readOnlyHint()).isFalse();
    }

    @Test
    void folderNamesAppearInTheLogOnlyInTheOptInTrace() throws Exception {
        ok("create_folder", args("account", account("basic"), "name", "Log" + MARKER));
        String log = Files.readString(config.logFile(), StandardCharsets.UTF_8);
        List<String> outside = log.lines().filter(l -> l.contains(MARKER) && !l.contains(TRACE_LOGGER)).toList();
        assertThat(outside).isEmpty();
        assertThat(log).as("the trace does show it").contains(MARKER);
    }
}
