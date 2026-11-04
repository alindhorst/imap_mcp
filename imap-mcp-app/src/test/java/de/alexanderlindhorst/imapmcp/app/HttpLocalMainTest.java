package de.alexanderlindhorst.imapmcp.app;

import static org.assertj.core.api.Assertions.assertThat;

import com.icegreen.greenmail.junit5.GreenMailExtension;
import com.icegreen.greenmail.util.ServerSetupTest;
import de.alexanderlindhorst.imapmcp.transport.http.HealthServlet;
import de.alexanderlindhorst.imapmcp.transport.http.HttpLocalServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.api.io.TempDir;

/** Subprocess tests of the http-local launcher: the real {@code Main --mode http-local} on a free loopback port. */
class HttpLocalMainTest {

    @RegisterExtension
    static final GreenMailExtension GREEN_MAIL = new GreenMailExtension(ServerSetupTest.IMAPS.dynamicPort());

    private static final String TOOLS_LIST = "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/list\",\"params\":{}}";

    @TempDir
    Path dir;

    private final HttpClient http = HttpClient.newHttpClient();

    private static int freePort() throws IOException {
        try (ServerSocket s = new ServerSocket(0)) {
            return s.getLocalPort();
        }
    }

    private Path config() throws IOException {
        return config("");
    }

    private Path config(String extra) throws IOException {
        GREEN_MAIL.setUser("user@localhost", "user", "pw");
        Path f = dir.resolve("config.yaml");
        Files.writeString(f, """
                accounts:
                  - id: work
                    host: 127.0.0.1
                    port: %d
                    username: user
                    password: "env:MAIL_PW"
                """.formatted(GREEN_MAIL.getImaps().getPort()) + extra);
        return f;
    }

    private AppProcess start(int port, Map<String, String> extraEnv) throws Exception {
        return start(port, extraEnv, config());
    }

    private AppProcess start(int port, Map<String, String> extraEnv, Path config) throws Exception {
        Map<String, String> env = new HashMap<>();
        env.put("MAIL_PW", "pw");
        env.put("IMAP_MCP_STATE_DIR", dir.resolve("state").toString());
        env.put("IMAP_MCP_HTTP_PORT", Integer.toString(port));
        env.putAll(extraEnv);
        return AppProcess.start(List.of(), List.of("--mode", "http-local", "--config", config.toString()), env)
                .pumpStdout();
    }

    private int healthStatus(int port) throws Exception {
        return http.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/health")).build(),
                HttpResponse.BodyHandlers.discarding()).statusCode();
    }

    private void awaitHealthy(AppProcess p, int port) throws Exception {
        long deadline = System.nanoTime() + 30_000_000_000L;
        while (System.nanoTime() < deadline) {
            if (!p.process().isAlive()) {
                throw new AssertionError("process exited early with " + p.process().exitValue() + ": " + p.stderr());
            }
            try {
                if (healthStatus(port) == 200) {
                    return;
                }
            } catch (IOException e) {
                Thread.sleep(100);
            }
        }
        throw new AssertionError("server did not become healthy");
    }

    private HttpResponse<String> mcp(int port, String token) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/mcp"))
                .header("Content-Type", "application/json").header("Accept", "application/json, text/event-stream")
                .POST(HttpRequest.BodyPublishers.ofString(TOOLS_LIST));
        if (token != null) {
            b.header("Authorization", "Bearer " + token);
        }
        return http.send(b.build(), HttpResponse.BodyHandlers.ofString());
    }

    private String token() throws IOException {
        return Files.readString(dir.resolve("state").resolve("http-token")).strip();
    }

    private static void stop(AppProcess p) throws Exception {
        p.process().destroy();
        assertThat(p.waitFor(15)).as("exits after SIGTERM").isTrue();
    }

    private String logText() throws IOException {
        return Files.readString(dir.resolve("state").resolve("logs").resolve("imap-mcp.log"));
    }

    // AC1

    @Test
    void firstStartCreatesTokenFileAndServesToolsThenSecondStartReusesIt() throws Exception {
        int port = freePort();
        String first;
        try (AppProcess p = start(port, Map.of())) {
            awaitHealthy(p, port);
            Path file = dir.resolve("state").resolve("http-token");
            assertThat(Files.getPosixFilePermissions(file)).isEqualTo(PosixFilePermissions.fromString("rw-------"));
            first = token();
            HttpResponse<String> r = mcp(port, first);
            assertThat(r.statusCode()).isEqualTo(200);
            for (String tool : List.of("list_accounts", "list_folders", "search_messages", "get_message",
                    "list_attachments", "set_flags", "move_messages", "copy_messages", "delete_messages",
                    "create_folder")) {
                assertThat(r.body()).contains(tool);
            }
            stop(p);
        }
        int port2 = freePort();
        try (AppProcess p = start(port2, Map.of())) {
            awaitHealthy(p, port2);
            assertThat(token()).isEqualTo(first);
            assertThat(mcp(port2, first).statusCode()).isEqualTo(200);
            stop(p);
        }
    }

    // AC2

    @Test
    void wrongTokenBadHostAndHealth() throws Exception {
        int port = freePort();
        try (AppProcess p = start(port, Map.of())) {
            awaitHealthy(p, port);
            assertThat(mcp(port, "wrong-token-wrong-token-wrong-token-0000").statusCode()).isEqualTo(401);
            assertThat(mcp(port, null).statusCode()).isEqualTo(401);
            assertThat(healthStatus(port)).isEqualTo(200);

            try (Socket s = new Socket("127.0.0.1", port)) {
                OutputStream o = s.getOutputStream();
                String body = TOOLS_LIST;
                o.write(("POST /mcp HTTP/1.1\r\nHost: evil.example.com\r\nAuthorization: Bearer " + token()
                        + "\r\nContent-Type: application/json\r\nAccept: application/json, text/event-stream\r\n"
                        + "Content-Length: " + body.length() + "\r\nConnection: close\r\n\r\n" + body)
                        .getBytes(StandardCharsets.UTF_8));
                o.flush();
                String status = new String(s.getInputStream().readNBytes(12), StandardCharsets.US_ASCII);
                assertThat(status).isEqualTo("HTTP/1.1 403");
            }
            stop(p);
        }
    }

    // AC3, AC4 (exit code), nothing on stdout

    @Test
    void tokenNeverAppearsInLogStdoutOrStderrAndSigtermExitsZero() throws Exception {
        int port = freePort();
        try (AppProcess p = start(port, Map.of())) {
            awaitHealthy(p, port);
            String token = token();
            assertThat(mcp(port, token).statusCode()).isEqualTo(200);
            assertThat(mcp(port, token + "x").statusCode()).isEqualTo(401);
            stop(p);
            assertThat(p.exitCode()).isZero();
            assertThat(logText()).contains("generated bearer token at").doesNotContain(token);
            assertThat(p.collectedStdout()).isEmpty();
            assertThat(p.stderr()).doesNotContain(token);
        }
    }

    // AC5

    @Test
    void nonLoopbackBindWithoutFlagExitsOneWithActionableMessage() throws Exception {
        int port = freePort();
        try (AppProcess p = start(port, Map.of("IMAP_MCP_HTTP_BIND", "0.0.0.0"))) {
            assertThat(p.waitFor(30)).isTrue();
            assertThat(p.exitCode()).isEqualTo(1);
            assertThat(p.stderr()).contains("not a loopback address").contains("allowNonLoopbackBind");
            assertThat(p.collectedStdout()).isEmpty();
        }
    }

    @Test
    void nonLoopbackBindWithFlagStartsAndLogsOneWarn() throws Exception {
        int port = freePort();
        try (AppProcess p = start(port, Map.of("IMAP_MCP_HTTP_BIND", "0.0.0.0",
                "IMAP_MCP_HTTP_ALLOW_NON_LOOPBACK_BIND", "true"))) {
            awaitHealthy(p, port);
            stop(p);
            assertThat(logText().lines().filter(l -> l.contains("WARN") && l.contains("not a loopback")).count())
                    .isEqualTo(1);
        }
    }

    @Test
    void defaultBindIsLoopbackOnly() throws Exception {
        int port = freePort();
        try (AppProcess p = start(port, Map.of())) {
            awaitHealthy(p, port);
            assertThat(logText()).contains("listening on 127.0.0.1:" + port);
            stop(p);
        }
    }

    @Test
    void badConfigExitsOneWithStdoutEmpty() throws Exception {
        Path bad = dir.resolve("bad.yaml");
        Files.writeString(bad, "bogusKey: 1\n");
        try (AppProcess p = AppProcess.start(List.of(), List.of("--mode", "http-local", "--config", bad.toString()),
                Map.of("IMAP_MCP_STATE_DIR", dir.resolve("state").toString())).pumpStdout()) {
            assertThat(p.waitFor(30)).isTrue();
            assertThat(p.exitCode()).isEqualTo(1);
            assertThat(p.stderr()).contains("startup failed");
            assertThat(p.collectedStdout()).isEmpty();
        }
    }

    // http.maxRequestBytes and server version are wired from config / manifest

    @Test
    void configuredMaxRequestBytesAndServerVersionAreApplied() throws Exception {
        int port = freePort();
        try (AppProcess p = start(port, Map.of(), config("http:\n  maxRequestBytes: 2KiB\n"))) {
            awaitHealthy(p, port);
            String token = token();
            HttpRequest init = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/mcp"))
                    .header("Content-Type", "application/json")
                    .header("Accept", "application/json, text/event-stream")
                    .header("Authorization", "Bearer " + token)
                    .POST(HttpRequest.BodyPublishers.ofString("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\","
                            + "\"params\":{\"protocolVersion\":\"2025-06-18\",\"capabilities\":{},"
                            + "\"clientInfo\":{\"name\":\"t\",\"version\":\"1\"}}}")).build();
            assertThat(http.send(init, HttpResponse.BodyHandlers.ofString()).body())
                    .contains("\"name\":\"imap-mcp\"").contains("\"version\":\"dev\"");
            HttpRequest big = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/mcp"))
                    .header("Content-Type", "application/json")
                    .header("Accept", "application/json, text/event-stream")
                    .header("Authorization", "Bearer " + token)
                    .POST(HttpRequest.BodyPublishers.ofString("{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tools/list\","
                            + "\"params\":{\"pad\":\"" + "x".repeat(4096) + "\"}}")).build();
            assertThat(http.send(big, HttpResponse.BodyHandlers.ofString()).statusCode()).isBetween(400, 499);
            assertThat(mcp(port, token).statusCode()).isEqualTo(200);
            stop(p);
        }
    }

    // AC4: ordering of the shutdown sequence; /health is 503 while the server is still stopping

    @Test
    void shutdownSequenceFlipsHealthBeforeStopThenClosesComponentsThenLogging() throws Exception {
        HealthServlet health = new HealthServlet();
        HttpLocalServer server = HttpLocalServer.builder().bind("127.0.0.1").port(0).healthServlet(health).build();
        server.start();
        List<String> events = new ArrayList<>();
        AtomicInteger healthDuringStop = new AtomicInteger();
        try {
            HttpLocalMain.shutdown(
                    () -> {
                        events.add("begin");
                        server.beginShutdown();
                    },
                    () -> {
                        events.add("stop");
                        healthDuringStop.set(http.send(HttpRequest
                                .newBuilder(URI.create("http://127.0.0.1:" + server.port() + "/health")).build(),
                                HttpResponse.BodyHandlers.discarding()).statusCode());
                        server.stop();
                    },
                    () -> events.add("components"), () -> events.add("logging"));
        } finally {
            server.stop();
        }
        assertThat(healthDuringStop).hasValue(503);
        assertThat(events).containsExactly("begin", "stop", "components", "logging");
    }

    @Test
    void failingShutdownStepDoesNotSkipTheRemainingSteps() {
        List<String> events = new ArrayList<>();
        HttpLocalMain.shutdown(() -> events.add("begin"), () -> {
            throw new IllegalStateException("boom");
        }, () -> events.add("components"), () -> events.add("logging"));
        assertThat(events).containsExactly("begin", "components", "logging");
    }
}
