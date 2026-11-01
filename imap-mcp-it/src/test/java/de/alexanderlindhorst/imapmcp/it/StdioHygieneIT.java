package de.alexanderlindhorst.imapmcp.it;

import static org.assertj.core.api.Assertions.assertThat;

import de.alexanderlindhorst.imapmcp.core.account.MailAccount;
import jakarta.mail.Folder;
import jakarta.mail.Message;
import jakarta.mail.Session;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeBodyPart;
import jakarta.mail.internet.MimeMessage;
import jakarta.mail.internet.MimeMultipart;
import jakarta.mail.util.ByteArrayDataSource;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.function.BiConsumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.eclipse.angus.mail.imap.IMAPFolder;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * stdio hygiene end to end (SPEC 5.2, 5.6, 5.8): the shaded jar as a raw subprocess (not the SDK client, which
 * tolerates bad lines) against Dovecot, root log level TRACE, once without (run A) and once with (run B) the opt-in
 * IMAP protocol trace. Every stdout byte must be JSON-RPC, Jetty/servlet classes must never load, and neither the
 * password nor (outside the opt-in trace) mail content may reach a log.
 */
@Testcontainers
class StdioHygieneIT {

    private static final String PASSWORD_MARKER = "PwMark9d41c7e2";
    private static final String USER_MARKER = "usermark9d41c7e2";
    private static final String SUBJECT_MARKER = "SubjectMark9d41c7e2";
    private static final String BODY_MARKER = "BodyMark9d41c7e2";
    private static final String FROM_MARKER = "frommark9d41c7e2@example.org";
    private static final String FILE_MARKER = "AttachMark9d41c7e2.pdf";
    private static final List<String> CONTENT_MARKERS =
            List.of(SUBJECT_MARKER, BODY_MARKER, FROM_MARKER, FILE_MARKER);

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final Pattern ENTRY_START = Pattern.compile("^\\d{4}-\\d\\d-\\d\\dT");
    private static final String TRACE_LOGGER = "imapmcp.imap.trace";

    @Container
    static final DovecotContainer DOVECOT = new DovecotContainer(PASSWORD_MARKER);

    @TempDir
    static Path dir;

    private static Run runA;
    private static Run runB;

    /** What one scenario run left behind. */
    private record Run(int requests, int exitCode, boolean exitedInTime, String stdout, String stderr,
            String logText, List<LogEntry> log, List<String> classLoadLog) {
    }

    private record LogEntry(String level, String logger, String text) {
    }

    @BeforeAll
    static void seedAndRunBoth() throws Exception {
        seed();
        runA = run("a", false);
        runB = run("b", true);
    }

    // AC1
    @Test
    void bothRunsExitZeroWithinFiveSecondsAfterStdinCloses() {
        for (Run r : List.of(runA, runB)) {
            assertThat(r.exitedInTime()).isTrue();
            assertThat(r.exitCode()).isZero();
        }
    }

    // AC2
    @Test
    void everyStdoutLineIsAJsonRpcMessageAndNothingElseIsOnStdout() {
        for (Run r : List.of(runA, runB)) {
            assertThat(r.stdout()).isNotEmpty().endsWith("\n").doesNotContain("\r");
            String body = r.stdout().substring(0, r.stdout().length() - 1);
            for (String line : body.split("\n", -1)) { // an empty line fails the JSON parse
                JsonNode n = JSON.readTree(line);
                assertThat(n.isObject()).as("line is a JSON object").isTrue();
                assertThat(n.path("jsonrpc").asString()).isEqualTo("2.0");
                boolean response = n.has("id") && (n.has("result") || n.has("error"));
                assertThat(response || n.has("method")).as("response or method: %s", n.propertyNames()).isTrue();
            }
        }
    }

    // AC3
    @Test
    void thereIsExactlyOneResponsePerRequestAndNoneForNotifications() {
        for (Run r : List.of(runA, runB)) {
            List<JsonNode> responses = lines(r.stdout()).stream()
                    .filter(n -> n.has("id") && (n.has("result") || n.has("error"))).toList();
            assertThat(responses).hasSize(r.requests());
            assertThat(responses.stream().map(n -> n.path("id").asLong()).distinct().count())
                    .isEqualTo(r.requests());
        }
    }

    // AC4
    @Test
    void noJettyOrServletClassIsEverLoaded() {
        for (Run r : List.of(runA, runB)) {
            assertThat(r.classLoadLog()).anyMatch(l -> l.contains("de.alexanderlindhorst.imapmcp.app."));
            assertThat(r.classLoadLog()).noneMatch(l -> l.contains("org.eclipse.jetty.")
                    || l.contains("jakarta.servlet."));
        }
    }

    // AC5
    @Test
    void withoutTraceNeitherPasswordNorContentReachesStderrOrTheLog() {
        List<String> secrets = new ArrayList<>(CONTENT_MARKERS);
        secrets.add(PASSWORD_MARKER);
        for (String secret : secrets) {
            assertThat(runA.stderr()).doesNotContain(secret);
            assertThat(runA.logText()).doesNotContain(secret);
        }
        assertThat(runA.log()).as("TRACE root level really produced log output").isNotEmpty();
        assertThat(runA.log()).noneMatch(e -> e.logger().equals(TRACE_LOGGER));
    }

    // AC6
    @Test
    void withTraceNoCredentialIsAnywhereAndContentIsOnlyInTraceLoggerLines() {
        for (String credential : List.of(PASSWORD_MARKER, USER_MARKER)) {
            assertThat(runB.stdout()).doesNotContain(credential);
            assertThat(runB.stderr()).doesNotContain(credential);
            assertThat(runB.logText()).doesNotContain(credential);
        }
        for (String marker : CONTENT_MARKERS) {
            assertThat(runB.stderr()).doesNotContain(marker);
        }
        assertThat(runB.log().stream().filter(e -> e.level().equals("WARN")
                && e.text().startsWith("IMAP protocol trace enabled"))).hasSize(1);
        List<LogEntry> traceEntries = runB.log().stream().filter(e -> e.logger().equals(TRACE_LOGGER)).toList();
        assertThat(traceEntries).isNotEmpty();
        assertThat(traceEntries.stream().anyMatch(e -> CONTENT_MARKERS.stream().anyMatch(e.text()::contains)))
                .as("the opt-in trace does show content").isTrue();
        for (LogEntry e : runB.log()) {
            if (!e.logger().equals(TRACE_LOGGER)) {
                for (String marker : CONTENT_MARKERS) {
                    assertThat(e.text()).as("log line of %s", e.logger()).doesNotContain(marker);
                }
            }
        }
    }

    // AC7
    @Test
    void contentMarkersAreOnStdoutOnlyBelowUntrustedPaths() {
        for (Run r : List.of(runA, runB)) {
            Map<String, List<String>> seenAt = new HashMap<>();
            for (JsonNode message : lines(r.stdout())) {
                walk(message, "$", (path, text) -> {
                    for (String marker : CONTENT_MARKERS) {
                        if (text.contains(marker)) {
                            assertThat(path).as("path of %s", marker).contains("untrusted");
                            seenAt.computeIfAbsent(marker, k -> new ArrayList<>()).add(path);
                        }
                    }
                });
            }
            assertThat(seenAt.keySet()).as("the markers really are returned, as labeled data")
                    .containsExactlyInAnyOrderElementsOf(CONTENT_MARKERS);
        }
    }

    // ---- scenario -----------------------------------------------------------------------------------------

    private static void seed() throws Exception {
        try (MailSeeder seeder = DOVECOT.seeder(USER_MARKER)) {
            MimeMessage m = new MimeMessage(Session.getInstance(new Properties()));
            m.setFrom(new InternetAddress(FROM_MARKER));
            m.setRecipient(Message.RecipientType.TO, new InternetAddress("recipient@example.org"));
            m.setSubject(SUBJECT_MARKER, "UTF-8");
            MimeMultipart mixed = new MimeMultipart("mixed");
            MimeBodyPart text = new MimeBodyPart();
            text.setText("Hello, " + BODY_MARKER, "UTF-8");
            mixed.addBodyPart(text);
            MimeBodyPart att = new MimeBodyPart();
            att.setDataHandler(new jakarta.activation.DataHandler(
                    new ByteArrayDataSource(new byte[] {1, 2, 3}, "application/pdf")));
            att.setFileName(FILE_MARKER);
            att.setDisposition("attachment");
            mixed.addBodyPart(att);
            m.setContent(mixed);
            IMAPFolder inbox = (IMAPFolder) seeder.store().getFolder("INBOX");
            inbox.appendMessages(new Message[] {m});
            assertThat(seeder.messageCount("INBOX")).isEqualTo(1);
        }
    }

    private static Run run(String name, boolean trace) throws Exception {
        Path root = Files.createDirectories(dir.resolve(name));
        Path classLog = root.resolve("cl.log");
        ServerConfig base = new ServerConfigWriter(root)
                .account("work", DOVECOT.getHost(), DOVECOT.imapsPort(), MailAccount.Security.IMPLICIT_TLS,
                        USER_MARKER, PASSWORD_MARKER)
                .account("dead", "127.0.0.1", closedPort(), MailAccount.Security.IMPLICIT_TLS, "nobody", "x")
                .trustStore(TestTrust.writeTruststore(root))
                .extraYaml("imap:\n  trace: " + trace + "\n").write();
        List<String> jvm = new ArrayList<>(base.jvmArgs());
        jvm.add("-Xlog:class+load=info:file=" + classLog);
        Map<String, String> env = new LinkedHashMap<>(base.env());
        env.put("IMAP_MCP_LOG_LEVEL", "TRACE");
        ServerConfig cfg = new ServerConfig(base.configFile(), base.stateDir(), base.logDir(), env, jvm);

        try (RunningServer server = JarLauncher.start(cfg, "stdio")) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            Thread.ofVirtual().start(() -> {
                try {
                    server.stdout().transferTo(out);
                } catch (IOException ignored) {
                    // process gone
                }
            });
            Dialog d = new Dialog(server, out);
            int requests = d.conversation();
            server.closeStdin();
            boolean exited = server.waitFor(5);
            if (!exited) {
                server.process().destroyForcibly().waitFor();
            }
            String stderr = server.stderr();
            Thread.sleep(200); // let the stdout pump drain the closed pipe
            String stdout = out.toString(StandardCharsets.UTF_8);
            String logText = readLogs(cfg.logDir());
            return new Run(requests, exited ? server.exitCode() : -1, exited, stdout, stderr, logText,
                    parseLog(logText), Files.readAllLines(classLog));
        }
    }

    /** The message sequence; sends requests one at a time and reads the answers it needs for the next. */
    private record Dialog(RunningServer server, ByteArrayOutputStream out) {

        int conversation() throws Exception {
            int requests = 0;
            server.send("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":{\"protocolVersion\":"
                    + "\"2025-11-25\",\"capabilities\":{},\"clientInfo\":{\"name\":\"it\",\"version\":\"1\"}}}");
            await(1);
            requests++;
            server.send("{\"jsonrpc\":\"2.0\",\"method\":\"notifications/initialized\"}");
            server.send("{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tools/list\"}");
            await(2);
            requests++;

            JsonNode accounts = await(call(3, "list_accounts", "{}")).path("result").path("structuredContent");
            requests++;
            String work = null;
            String dead = null;
            for (JsonNode a : accounts.path("accounts")) {
                String label = a.path("label").asString();
                if (label.equals("work")) {
                    work = a.path("account").asString();
                } else if (label.equals("dead")) {
                    dead = a.path("account").asString();
                }
            }
            assertThat(work).isNotNull();
            assertThat(dead).isNotNull();

            call(4, "list_folders", "{\"account\":\"" + work + "\"}");
            await(4);
            requests++;
            JsonNode found = await(call(5, "search_messages",
                    "{\"account\":\"" + work + "\",\"folder\":\"INBOX\"}")).path("result").path("structuredContent");
            requests++;
            String handle = found.path("messages").path(0).path("handle").asString();
            assertThat(handle).isNotEmpty();

            call(6, "get_message", "{\"account\":\"" + work + "\",\"message\":\"" + handle + "\"}");
            await(6);
            requests++;
            call(7, "list_attachments", "{\"account\":\"" + work + "\",\"message\":\"" + handle + "\"}");
            await(7);
            requests++;

            int mid = handle.length() / 2;
            String tampered = handle.substring(0, mid) + (handle.charAt(mid) == 'A' ? 'B' : 'A')
                    + handle.substring(mid + 1);
            JsonNode bad = await(call(8, "get_message",
                    "{\"account\":\"" + work + "\",\"message\":\"" + tampered + "\"}"));
            requests++;
            assertThat(bad.has("error") || bad.path("result").path("isError").asBoolean()).isTrue();

            JsonNode unreachable = await(call(9, "list_folders", "{\"account\":\"" + dead + "\"}"));
            requests++;
            assertThat(unreachable.has("error") || unreachable.path("result").path("isError").asBoolean())
                    .isTrue();

            // Ignored in v1 (SPEC 5.5); the session must go on.
            server.send("{\"jsonrpc\":\"2.0\",\"method\":\"notifications/cancelled\",\"params\":"
                    + "{\"requestId\":9,\"reason\":\"it\"}}");
            server.send("{\"jsonrpc\":\"2.0\",\"id\":10,\"method\":\"tools/list\"}");
            await(10);
            requests++;
            return requests;
        }

        private int call(int id, String tool, String arguments) throws IOException {
            server.send("{\"jsonrpc\":\"2.0\",\"id\":" + id + ",\"method\":\"tools/call\",\"params\":{\"name\":\""
                    + tool + "\",\"arguments\":" + arguments + "}}");
            return id;
        }

        private JsonNode await(int id) throws Exception {
            long deadline = System.nanoTime() + 60_000_000_000L;
            while (System.nanoTime() < deadline) {
                String text = out.toString(StandardCharsets.UTF_8);
                int end = text.lastIndexOf('\n');
                if (end >= 0) {
                    for (String line : text.substring(0, end).split("\n")) {
                        JsonNode n = JSON.readTree(line);
                        if (n.has("id") && n.path("id").asLong(-1) == id
                                && (n.has("result") || n.has("error"))) {
                            return n;
                        }
                    }
                }
                Thread.sleep(25);
            }
            throw new AssertionError("no response to request " + id + " within 60 s");
        }
    }

    // ---- helpers ------------------------------------------------------------------------------------------

    private static int closedPort() throws IOException {
        try (ServerSocket s = new ServerSocket(0)) {
            return s.getLocalPort();
        }
    }

    private static List<JsonNode> lines(String stdout) {
        return Stream.of(stdout.split("\n")).filter(l -> !l.isEmpty()).map(JSON::readTree).toList();
    }

    /**
     * Visits every string value (and key) with its JSON path. A tool result carries its structured content a
     * second time as the JSON text of {@code content[].text}; that text is parsed and walked under the path
     * {@code <path>.text(json)}, so the rule applies to the paths inside it, not to the opaque string.
     */
    private static void walk(JsonNode n, String path, BiConsumer<String, String> visitor) {
        if (n.isObject()) {
            for (Map.Entry<String, JsonNode> e : n.properties()) {
                visitor.accept(path + ".<key>", e.getKey());
                walk(e.getValue(), path + "." + e.getKey(), visitor);
            }
        } else if (n.isArray()) {
            for (int i = 0; i < n.size(); i++) {
                walk(n.get(i), path + "[" + i + "]", visitor);
            }
        } else if (n.isString()) {
            String s = n.asString();
            if (path.endsWith(".text") && path.contains(".content[") && s.startsWith("{")) {
                walk(JSON.readTree(s), path + "(json)", visitor);
            } else {
                visitor.accept(path, s);
            }
        }
    }

    private static String readLogs(Path logDir) throws IOException {
        StringBuilder all = new StringBuilder();
        try (Stream<Path> files = Files.list(logDir)) {
            for (Path f : files.sorted().toList()) {
                all.append(Files.readString(f, StandardCharsets.UTF_8));
            }
        }
        return all.toString();
    }

    /** Groups the log into events (continuation lines belong to the previous one) and extracts the logger. */
    private static List<LogEntry> parseLog(String text) {
        List<StringBuilder> raw = new ArrayList<>();
        for (String line : text.split("\n")) {
            if (ENTRY_START.matcher(line).find() || raw.isEmpty()) {
                raw.add(new StringBuilder(line));
            } else {
                raw.get(raw.size() - 1).append('\n').append(line);
            }
        }
        // timestamp LEVEL [thread] <mdc...> logger - message
        Pattern p = Pattern.compile("^\\S+ (\\w+)\\s+\\[[^\\]]*\\] (.*?)(\\S+) - (.*)$", Pattern.DOTALL);
        List<LogEntry> entries = new ArrayList<>();
        for (StringBuilder b : raw) {
            Matcher m = p.matcher(b);
            assertThat(m.matches()).as("unparseable log event start: %s", b.substring(0, Math.min(60, b.length())))
                    .isTrue();
            entries.add(new LogEntry(m.group(1), m.group(3), m.group(4)));
        }
        return entries;
    }
}
