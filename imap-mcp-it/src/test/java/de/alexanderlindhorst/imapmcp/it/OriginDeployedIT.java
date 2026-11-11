package de.alexanderlindhorst.imapmcp.it;

import static org.assertj.core.api.Assertions.assertThat;

import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.spec.McpSchema;
import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestMethodOrder;

/**
 * Origin and Host handling on {@code /mcp} through Caddy with the shipped config (#433, SPEC 5.3, 5.7, DEPLOYMENT
 * section 4): an absent Origin passes, any present one (also {@code https://claude.ai} and {@code null}) is refused
 * with 403 and no {@code WWW-Authenticate}; with {@code http.allowedOrigins: ["https://claude.ai"]} in the copy's
 * config and a restart that origin passes and another one is still refused; the shipped config is restored and
 * the restore is asserted; a linked account still reads after the restarts (state is in Postgres).
 *
 * <p>The Host check (ticket step 2) is not tested here: Caddy's site matches the Host name only, so a foreign name is
 * answered by Caddy itself with an empty 200 and never reaches the app (probed through the proxy), and the same
 * name with another port or none is forwarded and allowed by the app's {@code <host>:*} default. No Host value both
 * reaches the app and is refused, so the app's 403 cannot be shown through Caddy.
 *
 * <p>This class restarts the app of the stack that all deployed ITs share, so it runs last ({@code @Order} on the
 * class, {@code ClassOrderer$OrderAnnotation} in the {@code deployed-it} execution). It edits only the copy's
 * {@code app/config.yaml} under {@code target/}, never {@code dist/docker}. It uses its own Keycloak user and links
 * one Dovecot user, unlinked at the end. No secret is printed.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@Order(Integer.MAX_VALUE)
class OriginDeployedIT {

    private static final String INITIALIZE = "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":"
            + "{\"protocolVersion\":\"2025-06-18\",\"capabilities\":{},\"clientInfo\":{\"name\":\"origin-test\","
            + "\"version\":\"1\"}}}";
    private static final String CLAUDE = "https://claude.ai";

    private DeployedStack stack;
    private Path configFile;
    private String shippedConfig;
    private KeycloakAdmin.TestUser user;
    private DeployedDovecot.ImapUser mail;
    private AccountsClient web;
    private String account;
    private final List<McpSyncClient> mcpClients = new java.util.ArrayList<>();

    @BeforeAll
    void start() throws Exception {
        stack = DeployedStack.instance();
        configFile = stack.runDir().resolve("app/config.yaml");
        shippedConfig = Files.readString(configFile, StandardCharsets.UTF_8);
        user = new KeycloakAdmin(stack).createUser();
        mail = stack.dovecotUsers().get(0);
        web = new AccountsClient(stack);
        assertThat(web.login(user).statusCode()).isEqualTo(200);
        Map<String, String> f = new LinkedHashMap<>();
        f.put("label", "Origin test mail");
        f.put("host", DeployedDovecot.HOST);
        f.put("port", String.valueOf(DeployedDovecot.PORT));
        f.put("security", "IMPLICIT_TLS");
        f.put("username", mail.name());
        f.put("password", mail.password().value());
        f.put("access", "read_only");
        assertThat(web.post("/accounts/link", f).statusCode()).isEqualTo(303);
    }

    @AfterAll
    void restoreTheStack() throws Exception {
        try {
            // a failed restore inside a test must not leave the shared stack on the altered config
            if (!Files.readString(configFile, StandardCharsets.UTF_8).equals(shippedConfig)) {
                Files.writeString(configFile, shippedConfig, StandardCharsets.UTF_8);
                stack.restartApp();
            }
            McpSyncClient mcp = mcpFor(user);
            account = (String) list(call(mcp, "list_accounts"), "accounts").get(0).get("account");
            assertThat(web.post("/accounts/unlink", Map.of("account", account)).statusCode()).isEqualTo(303);
        } finally {
            mcpClients.forEach(McpSyncClient::close);
        }
    }

    // ---- 1. the shipped config -----------------------------------------------------------------------------------

    @Test
    @Order(1)
    void withTheShippedConfigAnAbsentOriginPassesAndAnyPresentOneIsRefused() throws Exception {
        String token = token();

        assertThat(initialize(token, null).statusCode()).as("no Origin").isEqualTo(200);
        assertRefused(initialize(token, CLAUDE));
        assertRefused(initialize(token, "null"));
    }

    // ---- 2. allowedOrigins configured ----------------------------------------------------------------------------

    @Test
    @Order(2)
    void withTheOriginConfiguredItPassesAndAnotherOneIsStillRefusedThenTheShippedConfigIsRestored() throws Exception {
        assertThat(shippedConfig).contains("\nhttp:\n").doesNotContain("allowedOrigins");
        try {
            Files.writeString(configFile, shippedConfig.replace("\nhttp:\n",
                    "\nhttp:\n  allowedOrigins: [\"" + CLAUDE + "\"]\n"), StandardCharsets.UTF_8);
            stack.restartApp();
            String token = token();

            assertThat(initialize(token, CLAUDE).statusCode()).isEqualTo(200);
            assertRefused(initialize(token, "https://evil.example"));
            assertThat(initialize(token, null).statusCode()).isEqualTo(200);
        } finally {
            Files.writeString(configFile, shippedConfig, StandardCharsets.UTF_8);
            stack.restartApp();
        }
        String token = token();
        assertThat(initialize(token, null).statusCode()).as("restored: no Origin").isEqualTo(200);
        assertRefused(initialize(token, CLAUDE));
    }

    // ---- 3. the state survives the restarts ----------------------------------------------------------------------

    @Test
    @Order(3)
    void aLinkedAccountStillReadsAfterTheRestarts() throws Exception {
        McpSyncClient mcp = mcpFor(user);

        List<Map<String, Object>> accounts = list(call(mcp, "list_accounts"), "accounts");
        assertThat(accounts).hasSize(1);
        assertThat(accounts.get(0)).containsEntry("status", "ok");
        account = (String) accounts.get(0).get("account");
        List<Map<String, Object>> folders = list(call(mcp, "list_folders", "account", account), "folders");
        assertThat(folders).anyMatch(f -> "INBOX".equals(f.get("folder")));
    }

    // ---- helpers -------------------------------------------------------------------------------------------------

    private String token() throws Exception {
        return ConnectorToken.obtain(stack, new ConnectorClient(stack), user);
    }

    private HttpResponse<String> initialize(String token, String origin) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(stack.configValue("oauth", "resource")))
                .header("Authorization", "Bearer " + token).header("Content-Type", "application/json")
                .header("Accept", "application/json, text/event-stream")
                .POST(HttpRequest.BodyPublishers.ofString(INITIALIZE));
        if (origin != null) {
            b.header("Origin", origin);
        }
        return new ConnectorClient(stack).send(b);
    }

    private static void assertRefused(HttpResponse<String> response) {
        assertThat(response.statusCode()).isEqualTo(403);
        assertThat(response.headers().allValues("WWW-Authenticate")).as("a 403 carries no challenge").isEmpty();
    }

    private McpSyncClient mcpFor(KeycloakAdmin.TestUser u) throws Exception {
        ConnectorClient client = new ConnectorClient(stack);
        McpSyncClient mcp = client.mcp(ConnectorToken.obtain(stack, client, u));
        mcpClients.add(mcp);
        mcp.initialize();
        return mcp;
    }

    private static Map<String, Object> call(McpSyncClient mcp, String tool, Object... kv) {
        Map<String, Object> args = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            args.put((String) kv[i], kv[i + 1]);
        }
        return ToolResults.payload(mcp.callTool(new McpSchema.CallToolRequest(tool, args)));
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> list(Map<String, Object> m, String key) {
        return (List<Map<String, Object>>) m.get(key);
    }
}
