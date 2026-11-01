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

    private static final Pattern CLIENT_COMMAND = Pattern.compile(" imapmcp\\.imap\\.trace - (A\\d+) (.*)$");

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
}
