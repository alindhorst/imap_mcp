package de.alexanderlindhorst.imapmcp.it;

import static org.assertj.core.api.Assertions.assertThat;

import de.alexanderlindhorst.imapmcp.core.account.MailAccount;
import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport;
import io.modelcontextprotocol.spec.McpSchema;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.net.Socket;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * http-local end to end (SPEC 2, 5.3, 5.8): the shaded jar on a free loopback port against Dovecot, driven by the
 * SDK's Streamable HTTP client with the generated bearer token; negative cases use raw HTTP.
 */
class HttpLocalIT {

    private static final String USER = "httpuser";
    private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

    private static DovecotContainer dovecot;
    private static ServerConfig config;
    private static int port;
    private static String token;
    private static RunningServer server;

    @TempDir
    static Path dir;

    @BeforeAll
    static void start() throws Exception {
        dovecot = new DovecotContainer();
        dovecot.start();
        try (MailSeeder seeder = dovecot.seeder(USER)) {
            seeder.appendPlain("INBOX", "Plain greeting", "hello over http");
            seeder.appendMultipart("INBOX", "With attachment", "text part", "<p>html part</p>",
                    List.of(new MailSeeder.Attachment("report.txt", "text/plain", "attachment data".getBytes(
                            StandardCharsets.UTF_8))));
        }
        port = freePort();
        config = writeConfig(dir, port);
        server = launch(config);
        token = Files.readString(config.stateDir().resolve("http-token")).trim();
    }

    @AfterAll
    static void stop() throws Exception {
        if (server != null) {
            server.close();
        }
        if (dovecot != null) {
            dovecot.stop();
        }
    }

    // ---- AC1 ----

    @Test
    void sdkClientWithTheGeneratedTokenInitializesListsTenToolsAndCallsEachOnce() throws Exception {
        try (McpSyncClient client = client(port, token)) {
            McpSchema.InitializeResult init = client.initialize();
            assertThat(init.serverInfo().name()).isNotBlank();

            assertThat(client.listTools().tools()).extracting(McpSchema.Tool::name).containsExactlyInAnyOrder(
                    "list_accounts", "list_folders", "search_messages", "get_message", "list_attachments", "set_flags",
                    "move_messages", "copy_messages", "delete_messages", "create_folder");

            Map<String, Object> accounts = call(client, "list_accounts", Map.of());
            String account = (String) ((Map<?, ?>) ((List<?>) accounts.get("accounts")).get(0)).get("account");

            Map<String, Object> folders = call(client, "list_folders", Map.of("account", account));
            assertThat(folders.get("folders").toString()).contains("INBOX");

            Map<String, Object> search = call(client, "search_messages",
                    Map.of("account", account, "folder", "INBOX"));
            List<?> messages = (List<?>) search.get("messages");
            assertThat(messages).hasSize(2);
            String handle = (String) ((Map<?, ?>) messages.get(0)).get("handle");

            Map<String, Object> message = call(client, "get_message", Map.of("account", account, "message", handle));
            assertThat(message).containsKey("handle");

            Map<String, Object> attachments = call(client, "list_attachments",
                    Map.of("account", account, "message", handle));
            assertThat(attachments).isNotEmpty();
        }
    }

    // ---- AC2 ----

    @Test
    void noTokenIs401WithBearerChallengeAndWrongTokenIs401InvalidToken() throws Exception {
        HttpResponse<String> none = post(port, null, null);
        assertThat(none.statusCode()).isEqualTo(401);
        assertThat(none.headers().firstValue("WWW-Authenticate")).hasValue("Bearer realm=\"imap-mcp\"");

        HttpResponse<String> wrong = post(port, "not-the-token", null);
        assertThat(wrong.statusCode()).isEqualTo(401);
        assertThat(wrong.headers().firstValue("WWW-Authenticate").orElseThrow())
                .contains("Bearer realm=\"imap-mcp\"").contains("error=\"invalid_token\"");
    }

    // ---- AC3 ----

    @Test
    void foreignHostIs403() throws Exception {
        assertThat(rawStatus(port, "POST", "evil.example", token)).isEqualTo(403);
        assertThat(rawStatus(port, "POST", "evil.example", null)).isEqualTo(403);
    }

    @Test
    void foreignOriginIs403EvenWithAValidToken() throws Exception {
        assertThat(post(port, token, "http://evil.example").statusCode()).isEqualTo(403);
    }

    // ---- AC4 ----

    @Test
    void getMcpIs405WithAndWithoutTokenAndHealthIsOpen() throws Exception {
        HttpRequest.Builder get = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/mcp")).GET();
        assertThat(HTTP.send(get.build(), HttpResponse.BodyHandlers.ofString()).statusCode()).isEqualTo(405);
        assertThat(HTTP.send(get.header("Authorization", "Bearer " + token).build(),
                HttpResponse.BodyHandlers.ofString()).statusCode()).isEqualTo(405);

        HttpResponse<String> health = HTTP.send(HttpRequest.newBuilder(
                URI.create("http://127.0.0.1:" + port + "/health")).GET().build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(health.statusCode()).isEqualTo(200);
        assertThat(health.body()).isEqualTo("{\"status\":\"ok\"}");
    }

    // ---- AC5 (the output checks run after the server was exercised by the tests above and below) ----

    @Test
    void tokenFileIsOwnerOnly() throws Exception {
        Path file = config.stateDir().resolve("http-token");
        assertThat(PosixFilePermissions.toString(Files.getPosixFilePermissions(file))).isEqualTo("rw-------");
    }

    @Test
    void tokenNeverAppearsInStdoutStderrOrLogFile() throws Exception {
        // Exercise the server (a successful and a rejected request) so that any token logging would have happened.
        try (McpSyncClient client = client(port, token)) {
            client.initialize();
            client.listTools();
        }
        post(port, "wrong-" + token, null);

        Path dir2 = Files.createTempDirectory(dir, "out");
        ServerConfig own = writeConfig(dir2, freePort());
        String ownToken;
        String stderr;
        String log;
        byte[] stdout;
        try (RunningServer s = launch(own)) {
            ownToken = Files.readString(own.stateDir().resolve("http-token")).trim();
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            Thread pump = Thread.ofVirtual().start(() -> drain(s.stdout(), out));
            int ownPort = portOf(own);
            try (McpSyncClient client = client(ownPort, ownToken)) {
                client.initialize();
                client.listTools();
            }
            post(ownPort, "wrong-" + ownToken, null);
            s.process().destroy();
            assertThat(s.waitFor(30)).isTrue();
            pump.join(5000);
            stdout = out.toByteArray();
            stderr = s.stderr();
        }
        log = Files.readString(own.logFile());
        assertThat(log).isNotBlank();
        assertThat(new String(stdout, StandardCharsets.UTF_8)).doesNotContain(ownToken);
        assertThat(stdout).isEmpty();
        assertThat(stderr).doesNotContain(ownToken);
        assertThat(log).doesNotContain(ownToken);
    }

    // ---- AC6 ----

    @Test
    void restartWithTheSameStateDirKeepsTheTokenAndMessageHandles() throws Exception {
        Path dir3 = Files.createTempDirectory(dir, "restart");
        int p = freePort();
        ServerConfig cfg = writeConfig(dir3, p);
        String account;
        String handle;
        String firstToken;
        try (RunningServer first = launch(cfg)) {
            firstToken = Files.readString(cfg.stateDir().resolve("http-token")).trim();
            try (McpSyncClient client = client(p, firstToken)) {
                client.initialize();
                account = (String) ((Map<?, ?>) ((List<?>) call(client, "list_accounts", Map.of())
                        .get("accounts")).get(0)).get("account");
                Map<String, Object> search = call(client, "search_messages",
                        Map.of("account", account, "folder", "INBOX"));
                handle = (String) ((Map<?, ?>) ((List<?>) search.get("messages")).get(0)).get("handle");
            }
            first.process().destroy();
            assertThat(first.waitFor(30)).isTrue();
        }

        try (RunningServer second = launch(cfg)) {
            assertThat(Files.readString(cfg.stateDir().resolve("http-token")).trim()).isEqualTo(firstToken);
            try (McpSyncClient client = client(p, firstToken)) {
                client.initialize();
                McpSchema.CallToolResult result = client.callTool(new McpSchema.CallToolRequest("get_message",
                        Map.of("account", account, "message", handle)));
                assertThat(result.isError()).isNotEqualTo(Boolean.TRUE);
                assertThat(result.structuredContent()).isInstanceOf(Map.class);
            }
        }
    }

    // ---- helpers ----

    private static ServerConfig writeConfig(Path root, int httpPort) throws Exception {
        ServerConfig cfg = new ServerConfigWriter(root)
                .account("work", dovecot.getHost(), dovecot.imapsPort(), MailAccount.Security.IMPLICIT_TLS, USER,
                        DovecotContainer.PASSWORD)
                .trustStore(TestTrust.writeTruststore(root))
                .extraYaml("http:\n  port: " + httpPort + "\n").write();
        return cfg;
    }

    private static int portOf(ServerConfig cfg) throws IOException {
        for (String line : Files.readAllLines(cfg.configFile())) {
            // the account port is indented four spaces; the http one exactly two
            if (line.startsWith("  port: ")) {
                return Integer.parseInt(line.substring(8).trim());
            }
        }
        throw new IllegalStateException("no http.port in config");
    }

    private static RunningServer launch(ServerConfig cfg) throws Exception {
        RunningServer s = JarLauncher.start(cfg, "http-local");
        int p = portOf(cfg);
        long deadline = System.nanoTime() + Duration.ofSeconds(60).toNanos();
        while (System.nanoTime() < deadline) {
            if (!s.process().isAlive()) {
                throw new IllegalStateException("server exited early: " + s.exitCode() + " " + s.stderr());
            }
            try {
                HttpResponse<String> r = HTTP.send(HttpRequest.newBuilder(
                        URI.create("http://127.0.0.1:" + p + "/health")).GET().build(),
                        HttpResponse.BodyHandlers.ofString());
                if (r.statusCode() == 200) {
                    return s;
                }
            } catch (IOException notYet) {
                Thread.sleep(200);
            }
        }
        s.close();
        throw new IllegalStateException("server did not become healthy");
    }

    private static McpSyncClient client(int p, String bearer) {
        return McpClient.sync(HttpClientStreamableHttpTransport.builder("http://127.0.0.1:" + p)
                .endpoint("/mcp")
                .httpRequestCustomizer((b, m, u, body, ctx) -> b.header("Authorization", "Bearer " + bearer))
                .build()).requestTimeout(Duration.ofSeconds(60)).initializationTimeout(Duration.ofSeconds(30))
                .build();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> call(McpSyncClient client, String tool, Map<String, Object> args) {
        McpSchema.CallToolResult r = client.callTool(new McpSchema.CallToolRequest(tool, args));
        assertThat(r.isError()).as(tool).isNotEqualTo(Boolean.TRUE);
        assertThat(r.structuredContent()).as(tool).isInstanceOf(Map.class);
        return (Map<String, Object>) r.structuredContent();
    }

    private static HttpResponse<String> post(int p, String bearer, String origin) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + p + "/mcp"))
                .header("Content-Type", "application/json").header("Accept", "application/json, text/event-stream")
                .POST(HttpRequest.BodyPublishers.ofString("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"ping\"}"));
        if (bearer != null) {
            b.header("Authorization", "Bearer " + bearer);
        }
        if (origin != null) {
            b.header("Origin", origin);
        }
        return HTTP.send(b.build(), HttpResponse.BodyHandlers.ofString());
    }

    /** Status of a request with an arbitrary Host header (java.net.http forbids setting it), over a raw socket. */
    private static int rawStatus(int p, String method, String host, String bearer) throws IOException {
        try (Socket s = new Socket("127.0.0.1", p)) {
            s.setSoTimeout(10_000);
            String body = "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"ping\"}";
            StringBuilder req = new StringBuilder(method).append(" /mcp HTTP/1.1\r\nHost: ").append(host)
                    .append("\r\nContent-Type: application/json\r\nAccept: application/json, text/event-stream\r\n");
            if (bearer != null) {
                req.append("Authorization: Bearer ").append(bearer).append("\r\n");
            }
            req.append("Content-Length: ").append(body.length()).append("\r\nConnection: close\r\n\r\n").append(body);
            s.getOutputStream().write(req.toString().getBytes(StandardCharsets.UTF_8));
            s.getOutputStream().flush();
            String status = new java.io.BufferedReader(new java.io.InputStreamReader(s.getInputStream(),
                    StandardCharsets.UTF_8)).readLine();
            return Integer.parseInt(status.split(" ")[1]);
        }
    }

    private static void drain(InputStream in, ByteArrayOutputStream out) {
        try {
            in.transferTo(out);
        } catch (IOException ignored) {
            // process gone
        }
    }

    private static int freePort() throws IOException {
        try (ServerSocket s = new ServerSocket(0)) {
            return s.getLocalPort();
        }
    }
}
