package de.alexanderlindhorst.imapmcp.it;

import static org.assertj.core.api.Assertions.assertThat;

import de.alexanderlindhorst.imapmcp.core.account.MailAccount;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The write startup guard of SPEC 5.5 / 5.7 through the shaded jar (stdio): {@code tools.deadline} below 30 s while
 * any account is writable fails startup naming the key; the same deadline with every account {@code readOnly} starts
 * and registers no write tool. No mail server is contacted (tools/list does not connect), so no container is needed.
 */
class WriteStartupGuardIT {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final List<String> WRITE_TOOLS =
            List.of("set_flags", "move_messages", "copy_messages", "delete_messages", "create_folder");

    @TempDir
    Path dir;

    private ServerConfig config(boolean readOnly) throws Exception {
        int unusedPort;
        try (ServerSocket s = new ServerSocket(0)) {
            unusedPort = s.getLocalPort();
        }
        ServerConfigWriter w = new ServerConfigWriter(dir)
                .account("work", "127.0.0.1", unusedPort, MailAccount.Security.IMPLICIT_TLS, "alice", "pw")
                .extraYaml("tools:\n  deadline: 20s\n");
        return (readOnly ? w.readOnly("work") : w).write();
    }

    private static JsonNode jsonRpc(String line) {
        JsonNode n = JSON.readTree(line); // a non-JSON line fails the parse
        assertThat(n.path("jsonrpc").asString()).as("every stdout line is JSON-RPC").isEqualTo("2.0");
        return n;
    }

    @Test
    void writableAccountWithTwentySecondDeadlineFailsStartupNamingToolsDeadline() throws Exception {
        try (RunningServer server = JarLauncher.start(config(false), "stdio")) {
            assertThat(server.waitFor(30)).as("process exits on its own").isTrue();
            assertThat(server.exitCode()).isEqualTo(1);
            assertThat(server.stderr()).contains("startup failed").contains("tools.deadline")
                    .contains("30s").doesNotContain("\tat ");
            assertThat(server.stdout().readAllBytes()).as("stdout stays empty").isEmpty();
        }
    }

    @Test
    void allAccountsReadOnlyWithTwentySecondDeadlineStartsAndListsNoWriteTool() throws Exception {
        try (RunningServer server = JarLauncher.start(config(true), "stdio")) {
            server.send("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":{\"protocolVersion\":"
                    + "\"2025-11-25\",\"capabilities\":{},\"clientInfo\":{\"name\":\"it\",\"version\":\"1\"}}}");
            server.send("{\"jsonrpc\":\"2.0\",\"method\":\"notifications/initialized\"}");
            server.send("{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tools/list\"}");
            // Read until the tools/list answer arrived (closing stdin earlier may end the server first), then the rest.
            BufferedReader out = new BufferedReader(new InputStreamReader(server.stdout(), StandardCharsets.UTF_8));
            List<JsonNode> lines = new ArrayList<>();
            JsonNode list = null;
            String line;
            while (list == null && (line = out.readLine()) != null) {
                JsonNode n = jsonRpc(line);
                lines.add(n);
                if (n.path("id").asInt() == 2) {
                    list = n;
                }
            }
            assertThat(list).as("tools/list answered").isNotNull();
            server.closeStdin();
            assertThat(server.waitFor(30)).isTrue();
            assertThat(server.exitCode()).isZero();
            while ((line = out.readLine()) != null) {
                lines.add(jsonRpc(line));
            }
            List<String> names = new ArrayList<>();
            list.path("result").path("tools").forEach(t -> names.add(t.path("name").asString()));
            assertThat(names).contains("search_messages", "list_accounts").doesNotContainAnyElementsOf(WRITE_TOOLS);
        }
    }
}
