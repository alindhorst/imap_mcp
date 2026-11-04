package de.alexanderlindhorst.imapmcp.it;

import static org.assertj.core.api.Assertions.assertThat;

import de.alexanderlindhorst.imapmcp.core.account.MailAccount;
import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.ServerParameters;
import io.modelcontextprotocol.client.transport.StdioClientTransport;
import io.modelcontextprotocol.json.McpJsonDefaults;
import io.modelcontextprotocol.spec.McpSchema;
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

/**
 * One server process (stdio, shaded jar, {@code imap.trace: true}) against one Dovecot container, with its own log.
 * Lets a test assert something about the whole wire of a single server, e.g. that no ENABLE ever went out against the
 * {@code NO_UTF8_ACCEPT} profile, which a log shared with the other profiles cannot show. Accounts are labelled by
 * Dovecot user name.
 */
final class TracedServer implements AutoCloseable {

    private static final Pattern CLIENT_COMMAND =
            Pattern.compile(" imapmcp\\.imap\\.trace - ([A-Z]+\\d+) (?!(?:OK|NO|BAD)\\b)([A-Z][A-Z.]*\\b.*)$");

    final ServerConfig config;
    final McpSyncClient client;
    private final Map<String, String> accounts = new LinkedHashMap<>();

    TracedServer(DovecotContainer dovecot, Path dir, String... users) throws Exception {
        ServerConfigWriter writer = new ServerConfigWriter(dir).trustStore(TestTrust.writeTruststore(dir))
                .extraYaml("imap:\n  trace: true\n");
        for (String u : users) {
            writer.account(u, dovecot.getHost(), dovecot.imapsPort(), MailAccount.Security.IMPLICIT_TLS, u,
                    DovecotContainer.PASSWORD);
        }
        config = writer.write();
        List<String> cmd = JarLauncher.builder(config, "stdio").command();
        ServerParameters params = ServerParameters.builder(cmd.get(0)).args(cmd.subList(1, cmd.size()))
                .env(config.env()).build();
        client = McpClient.sync(new StdioClientTransport(params, McpJsonDefaults.getMapper()))
                .requestTimeout(Duration.ofSeconds(60)).initializationTimeout(Duration.ofSeconds(60)).build();
        client.initialize();
        McpSchema.CallToolResult r = client.callTool(new McpSchema.CallToolRequest("list_accounts", Map.of()));
        for (Map<String, Object> a : list(asMap(r.structuredContent()), "accounts")) {
            accounts.put((String) a.get("label"), (String) a.get("account"));
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(Object o) {
        return (Map<String, Object>) o;
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> list(Map<String, Object> m, String key) {
        return (List<Map<String, Object>>) m.get(key);
    }

    /** The structured result of a successful tool call on the account of {@code user}. */
    Map<String, Object> ok(String tool, String user, Object... kv) {
        Map<String, Object> args = new LinkedHashMap<>();
        args.put("account", accounts.get(user));
        for (int i = 0; i < kv.length; i += 2) {
            args.put((String) kv[i], kv[i + 1]);
        }
        McpSchema.CallToolResult r = client.callTool(new McpSchema.CallToolRequest(tool, args));
        assertThat(r.isError()).as("%s -> %s", tool, r.structuredContent()).isNotEqualTo(Boolean.TRUE);
        return asMap(r.structuredContent());
    }

    long logSize() throws IOException {
        return Files.exists(config.logFile()) ? Files.size(config.logFile()) : 0;
    }

    /** Client commands (tag stripped) the server sent since {@code offset}; 0 is the whole life of the process. */
    List<String> commandsSince(long offset) throws IOException {
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

    @Override
    public void close() {
        client.close();
    }
}
