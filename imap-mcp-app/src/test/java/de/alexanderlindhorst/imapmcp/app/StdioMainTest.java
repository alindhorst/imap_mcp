package de.alexanderlindhorst.imapmcp.app;

import static org.assertj.core.api.Assertions.assertThat;

import com.icegreen.greenmail.junit5.GreenMailExtension;
import com.icegreen.greenmail.util.ServerSetupTest;
import io.modelcontextprotocol.json.McpJsonDefaults;
import io.modelcontextprotocol.json.McpJsonMapper;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.security.cert.Certificate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.api.io.TempDir;

/** Subprocess tests of the stdio launcher: the real {@code Main --mode stdio} against a GreenMail account. */
class StdioMainTest {

    @RegisterExtension
    static final GreenMailExtension GREEN_MAIL = new GreenMailExtension(ServerSetupTest.IMAPS.dynamicPort());

    private static final McpJsonMapper JSON = McpJsonDefaults.getMapper();

    @TempDir
    Path dir;

    /** Trust for the child JVM only via standard JSSE properties; there is no production switch for it. */
    private Path truststore() throws Exception {
        KeyStore source = KeyStore.getInstance("PKCS12");
        try (InputStream in = com.icegreen.greenmail.util.DummySSLServerSocketFactory.class
                .getResourceAsStream("/greenmail.p12")) {
            source.load(in, "changeit".toCharArray());
        }
        KeyStore trust = KeyStore.getInstance("PKCS12");
        trust.load(null, null);
        for (String alias : Collections.list(source.aliases())) {
            Certificate c = source.getCertificate(alias);
            if (c != null) {
                trust.setCertificateEntry(alias, c);
            }
        }
        Path file = dir.resolve("truststore.p12");
        try (OutputStream out = Files.newOutputStream(file)) {
            trust.store(out, "changeit".toCharArray());
        }
        return file;
    }

    private Path validConfig() throws IOException {
        GREEN_MAIL.setUser("user@localhost", "user", "pw");
        Path f = dir.resolve("config.yaml");
        Files.writeString(f, """
                accounts:
                  - id: work
                    host: 127.0.0.1
                    port: %d
                    username: user
                    password: "env:MAIL_PW"
                """.formatted(GREEN_MAIL.getImaps().getPort()));
        return f;
    }

    private AppProcess start(Path config, Map<String, String> extraEnv) throws Exception {
        Map<String, String> env = new java.util.HashMap<>();
        env.put("MAIL_PW", "pw");
        env.put("IMAP_MCP_STATE_DIR", dir.resolve("state").toString());
        env.putAll(extraEnv);
        return AppProcess.start(
                List.of("-Djavax.net.ssl.trustStore=" + truststore(), "-Djavax.net.ssl.trustStorePassword=changeit",
                        "-Djavax.net.ssl.trustStoreType=PKCS12"),
                List.of("--mode", "stdio", "--config", config.toString()), env);
    }

    /** Minimal newline-delimited JSON-RPC client over the child's stdin/stdout. */
    private static final class Client {
        final OutputStream out;
        final BufferedReader in;
        final List<String> lines = new ArrayList<>();
        int id = 1;

        Client(Process p) {
            out = p.getOutputStream();
            in = new BufferedReader(new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8));
        }

        @SuppressWarnings("unchecked")
        Map<String, Object> call(String method, Map<String, Object> params) throws IOException {
            int myId = id++;
            send(Map.of("jsonrpc", "2.0", "id", myId, "method", method, "params", params));
            String line;
            while ((line = in.readLine()) != null) {
                lines.add(line);
                Map<String, Object> msg = JSON.readValue(line, Map.class);
                if (msg.get("id") instanceof Number n && n.intValue() == myId) {
                    return msg;
                }
            }
            throw new AssertionError("stdout closed before the response to " + method);
        }

        void notify(String method) throws IOException {
            send(Map.of("jsonrpc", "2.0", "method", method));
        }

        private void send(Map<String, Object> msg) throws IOException {
            out.write((JSON.writeValueAsString(msg) + "\n").getBytes(StandardCharsets.UTF_8));
            out.flush();
        }

        void initialize() throws IOException {
            call("initialize", Map.of("protocolVersion", io.modelcontextprotocol.spec.ProtocolVersions.MCP_2025_11_25,
                    "capabilities", Map.of(), "clientInfo", Map.of("name", "test", "version", "1")));
            notify("notifications/initialized");
        }

        void rest() throws IOException {
            String line;
            while ((line = in.readLine()) != null) {
                lines.add(line);
            }
        }
    }

    @SuppressWarnings("unchecked")
    private static List<String> toolNames(Map<String, Object> response) {
        Map<String, Object> result = (Map<String, Object>) response.get("result");
        return ((List<Map<String, Object>>) result.get("tools")).stream().map(t -> (String) t.get("name")).toList();
    }

    // AC2, AC5

    @Test
    void serveInitializeAndToolsListThenExitZeroOnStdinEof() throws Exception {
        try (AppProcess p = start(validConfig(), Map.of())) {
            Client client = new Client(p.process());
            client.initialize();
            Map<String, Object> tools = client.call("tools/list", Map.of());
            assertThat(toolNames(tools)).containsExactlyInAnyOrder("list_accounts", "list_folders",
                    "search_messages", "get_message", "list_attachments");

            Map<String, Object> accounts = client.call("tools/call", Map.of("name", "list_accounts", "arguments", Map.of()));
            assertThat(accounts).containsKey("result");

            client.out.close();
            long start = System.nanoTime();
            assertThat(p.waitFor(5)).as("exits within 5 s of stdin EOF").isTrue();
            assertThat((System.nanoTime() - start) / 1_000_000_000.0).isLessThan(5.0);
            assertThat(p.exitCode()).isZero();

            client.rest();
            // AC5: every stdout line is a JSON-RPC message
            assertThat(client.lines).isNotEmpty().allSatisfy(line -> {
                @SuppressWarnings("unchecked")
                Map<String, Object> msg = JSON.readValue(line, Map.class);
                assertThat(msg).containsEntry("jsonrpc", "2.0");
            });
        }
    }

    // AC3

    @Test
    void badConfigExitsOneWithErrorOnStderrAndEmptyStdout() throws Exception {
        Path bad = dir.resolve("bad.yaml");
        Files.writeString(bad, "bogusKey: 1\n");
        try (AppProcess p = start(bad, Map.of())) {
            assertThat(p.waitFor(20)).isTrue();
            assertThat(p.exitCode()).isEqualTo(1);
            assertThat(p.stderr()).isNotBlank().contains("bogusKey").doesNotContain("\tat ");
            assertThat(p.stdoutRest()).isEmpty();
        }
    }

    @Test
    void missingConfigFileExitsOne() throws Exception {
        try (AppProcess p = start(dir.resolve("nope.yaml"), Map.of())) {
            assertThat(p.waitFor(20)).isTrue();
            assertThat(p.exitCode()).isEqualTo(1);
            assertThat(p.stderr()).contains("config file not found");
            assertThat(p.stdoutRest()).isEmpty();
        }
    }

    // AC6

    private void runAndStop(Map<String, String> env) throws Exception {
        try (AppProcess p = start(validConfig(), env)) {
            Client client = new Client(p.process());
            client.initialize();
            client.out.close();
            assertThat(p.waitFor(10)).isTrue();
            assertThat(p.exitCode()).isZero();
        }
    }

    @Test
    void logFileIsCreatedUnderConfiguredLogDir() throws Exception {
        Path logs = dir.resolve("custom-logs");
        runAndStop(Map.of("IMAP_MCP_LOG_DIR", logs.toString()));
        assertThat(logs.resolve("imap-mcp.log")).exists();
    }

    @Test
    void logFileDefaultsToStateDirLogs() throws Exception {
        runAndStop(Map.of());
        assertThat(dir.resolve("state").resolve("logs").resolve("imap-mcp.log")).exists();
    }

    @Test
    void logFileHasNoMailContentOrCredentials() throws Exception {
        runAndStop(Map.of());
        try (Stream<String> lines = Files.lines(dir.resolve("state").resolve("logs").resolve("imap-mcp.log"))) {
            assertThat(lines.toList()).noneMatch(l -> l.contains("pw\"") || l.contains("password"));
        }
    }
}
