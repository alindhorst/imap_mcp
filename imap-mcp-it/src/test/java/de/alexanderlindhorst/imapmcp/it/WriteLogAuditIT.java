package de.alexanderlindhorst.imapmcp.it;

import static org.assertj.core.api.Assertions.assertThat;

import de.alexanderlindhorst.imapmcp.core.account.MailAccount;
import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.ServerParameters;
import io.modelcontextprotocol.client.transport.StdioClientTransport;
import io.modelcontextprotocol.json.McpJsonDefaults;
import io.modelcontextprotocol.spec.McpSchema;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Log audit (#207, SPEC 5.6): every tool runs through the shaded jar (stdio) against Dovecot with the root log level
 * {@code DEBUG} (the IMAP trace stays off). The log must contain no folder name, keyword, handle ({@code m1.}),
 * credential or server text, and exactly one INFO "write call" line per write call, also for whole-call errors.
 * Markers are unique strings used as folder names, keyword, user name and password.
 */
class WriteLogAuditIT {

    private static final String PASSWORD = "pwmarkerhardening";
    private static final String USER = "usermarkerhardening";
    private static final String FOLDER_SRC = "ZzMarkerSource";
    private static final String FOLDER_TARGET = "ZzMarkerTarget";
    private static final String FOLDER_NEW = "ZzMarkerCreated";
    private static final String FOLDER_MISSING = "ZzMarkerMissing";
    private static final String KEYWORD = "zzmarkerkw";
    private static final String SUBJECT = "ZzMarkerSubject";

    @TempDir
    Path dir;

    @SuppressWarnings("unchecked")
    private static Map<String, Object> structured(McpSchema.CallToolResult r) {
        return ToolResults.payload(r);
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> list(Map<String, Object> m, String key) {
        return (List<Map<String, Object>>) m.get(key);
    }

    private static Map<String, Object> args(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            m.put((String) kv[i], kv[i + 1]);
        }
        return m;
    }

    @Test
    void debugLogHoldsNoNamesHandlesKeywordsOrServerTextAndOneInfoLinePerWriteCall() throws Exception {
        DovecotContainer dovecot = new DovecotContainer(PASSWORD);
        dovecot.start();
        try (MailSeeder seeder = dovecot.seeder(USER)) {
            seeder.appendPlain(FOLDER_SRC, SUBJECT + " 1", "ZzMarkerBody");
            seeder.appendPlain(FOLDER_SRC, SUBJECT + " 2", "ZzMarkerBody");
            seeder.appendPlain(FOLDER_SRC, SUBJECT + " 3", "ZzMarkerBody");
            seeder.appendPlain(FOLDER_SRC, SUBJECT + " 4", "ZzMarkerBody");
            seeder.appendPlain(FOLDER_TARGET, SUBJECT + " seed", "ZzMarkerBody");
        }
        ServerConfigWriter writer = new ServerConfigWriter(dir).trustStore(TestTrust.writeTruststore(dir))
                .account("work", dovecot.getHost(), dovecot.imapsPort(), MailAccount.Security.IMPLICIT_TLS, USER,
                        PASSWORD)
                .account("ro", dovecot.getHost(), dovecot.imapsPort(), MailAccount.Security.IMPLICIT_TLS, USER,
                        PASSWORD)
                .readOnly("ro");
        ServerConfig base = writer.write();
        Map<String, String> env = new LinkedHashMap<>(base.env());
        env.put("IMAP_MCP_LOG_LEVEL", "DEBUG");
        ServerConfig config = new ServerConfig(base.configFile(), base.stateDir(), base.logDir(), Map.copyOf(env),
                base.jvmArgs());
        List<String> cmd = JarLauncher.builder(config, "stdio").command();
        ServerParameters params = ServerParameters.builder(cmd.get(0)).args(cmd.subList(1, cmd.size()))
                .env(config.env()).build();

        int writeCalls = 0;
        try (McpSyncClient client = McpClient.sync(new StdioClientTransport(params, McpJsonDefaults.getMapper()))
                .requestTimeout(Duration.ofSeconds(90)).initializationTimeout(Duration.ofSeconds(60)).build()) {
            client.initialize();
            Map<String, String> accounts = new LinkedHashMap<>();
            for (Map<String, Object> a : list(structured(client.callTool(
                    new McpSchema.CallToolRequest("list_accounts", Map.of()))), "accounts")) {
                accounts.put((String) a.get("label"), (String) a.get("account"));
            }
            String work = accounts.get("work");
            String ro = accounts.get("ro");

            McpSchema.CallToolResult search = client.callTool(new McpSchema.CallToolRequest("search_messages",
                    args("account", work, "folder", FOLDER_SRC)));
            List<String> h = new ArrayList<>();
            list(structured(search), "messages").forEach(m -> h.add((String) m.get("handle")));
            assertThat(h).hasSize(4);
            assertThat(client.callTool(new McpSchema.CallToolRequest("get_message",
                    args("account", work, "message", h.get(0)))).isError()).isNotEqualTo(true);

            List<McpSchema.CallToolRequest> writes = List.of(
                    new McpSchema.CallToolRequest("set_flags", args("account", work, "handles", List.of(h.get(0)),
                            "seen", true)),
                    new McpSchema.CallToolRequest("set_flags", args("account", work, "handles", List.of(h.get(0)),
                            "add_keywords", List.of(KEYWORD))),
                    new McpSchema.CallToolRequest("create_folder", args("account", work, "name", FOLDER_NEW)),
                    new McpSchema.CallToolRequest("copy_messages", args("account", work, "handles",
                            List.of(h.get(1)), "target_folder", FOLDER_TARGET)),
                    new McpSchema.CallToolRequest("move_messages", args("account", work, "handles",
                            List.of(h.get(2)), "target_folder", FOLDER_TARGET)),
                    new McpSchema.CallToolRequest("delete_messages", args("account", work, "handles",
                            List.of(h.get(3)))),
                    // whole-call errors also log one line each
                    new McpSchema.CallToolRequest("move_messages", args("account", work, "handles",
                            List.of(h.get(0)), "target_folder", FOLDER_MISSING)),
                    new McpSchema.CallToolRequest("set_flags", args("account", ro, "handles", List.of(h.get(0)),
                            "seen", true)));
            for (McpSchema.CallToolRequest w : writes) {
                McpSchema.CallToolResult r = client.callTool(w);
                writeCalls++;
                assertThat(ToolResults.payload(r)).as(w.name()).isNotNull();
            }
            assertThat(client.callTool(new McpSchema.CallToolRequest("search_messages",
                    args("account", work, "folder", FOLDER_TARGET))).isError()).isNotEqualTo(true);
        }

        assertThat(Files.exists(config.logFile())).isTrue();
        String log;
        try (Stream<Path> files = Files.list(config.logDir())) {
            StringBuilder sb = new StringBuilder();
            for (Path f : files.sorted().toList()) {
                sb.append(Files.readString(f));
            }
            log = sb.toString();
        }
        assertThat(log).as("DEBUG is active: the audit is not vacuous").contains(" DEBUG ");
        assertThat(log).doesNotContain(FOLDER_SRC, FOLDER_TARGET, FOLDER_NEW, FOLDER_MISSING, KEYWORD, SUBJECT,
                "ZzMarkerBody", PASSWORD, USER, "m1.", "Copy completed", "Move completed", "COPYUID", "UIDVALIDITY");
        List<String> writeLines = log.lines().filter(l -> l.contains("write call tool=")).toList();
        assertThat(writeLines).as("one INFO line per write call").hasSize(writeCalls).allMatch(l -> l.contains(" INFO "));
        for (String tool : List.of("set_flags", "create_folder", "copy_messages", "move_messages", "delete_messages")) {
            assertThat(writeLines).anyMatch(l -> l.contains("tool=" + tool));
        }
        assertThat(writeLines).filteredOn(l -> l.contains("error_code=")).hasSize(2);
        dovecot.stop();
    }
}
