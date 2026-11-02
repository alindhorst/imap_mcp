package de.alexanderlindhorst.imapmcp.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.icegreen.greenmail.junit5.GreenMailExtension;
import com.icegreen.greenmail.util.GreenMailUtil;
import com.icegreen.greenmail.util.ServerSetupTest;
import de.alexanderlindhorst.imapmcp.app.config.AppConfig;
import de.alexanderlindhorst.imapmcp.app.config.AppPaths;
import de.alexanderlindhorst.imapmcp.app.config.ConfigLoader;
import de.alexanderlindhorst.imapmcp.core.tenancy.AccountContext;
import de.alexanderlindhorst.imapmcp.core.tenancy.TenantId;
import de.alexanderlindhorst.imapmcp.store.secret.BinaryProbe;
import de.alexanderlindhorst.imapmcp.store.secret.CommandRunner;
import de.alexanderlindhorst.imapmcp.store.secret.Platform;
import io.modelcontextprotocol.common.McpTransportContext;
import io.modelcontextprotocol.server.McpServerFeatures;
import io.modelcontextprotocol.server.McpStatelessServerFeatures;
import io.modelcontextprotocol.server.McpSyncServerExchange;
import io.modelcontextprotocol.spec.McpSchema;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.api.io.TempDir;

class AppComponentsTest {

    @RegisterExtension
    static final GreenMailExtension GREEN_MAIL = new GreenMailExtension(ServerSetupTest.IMAPS.dynamicPort());

    private static final List<String> TOOLS =
            List.of("list_accounts", "list_folders", "search_messages", "get_message", "list_attachments");
    private static final AccountContext LOCAL = new AccountContext(new TenantId("local"));

    @TempDir
    Path dir;

    private final List<AppComponents> created = new ArrayList<>();

    @AfterEach
    void closeAll() {
        created.forEach(AppComponents::close);
    }

    private AppConfig config(String password) throws IOException {
        return config(password, "");
    }

    private AppConfig config(String password, String extra) throws IOException {
        Path f = dir.resolve("config.yaml");
        Files.writeString(f, """
                accounts:
                  - id: work
                    host: 127.0.0.1
                    port: %d
                    username: user
                    password: "%s"
                """.formatted(GREEN_MAIL.getImaps().getPort(), password) + extra);
        return ConfigLoader.load(f, Map.of());
    }

    private AppPaths paths() {
        return new AppPaths(dir.resolve("config.yaml"), dir.resolve("state"), dir.resolve("logs"));
    }

    private static final CommandRunner NO_RUNNER = (argv, t) -> {
        throw new IOException("no command expected");
    };

    private AppComponents create(AppConfig c, CommandRunner runner) {
        return create(c, runner, Map.of("MAIL_PW", "pw"));
    }

    private AppComponents create(AppConfig c, CommandRunner runner, Map<String, String> env) {
        AppComponents a = AppComponents.create(c, paths(), Clock.systemUTC(), env, p -> {
            p.put("mail.imaps.ssl.trust", "*");
            p.put("mail.imaps.ssl.checkserveridentity", "false");
            return p;
        }, Platform.LINUX, runner, platform -> true);
        created.add(a);
        return a;
    }

    private AppComponents create() throws IOException {
        GREEN_MAIL.setUser("user@localhost", "user", "pw");
        return create(config("env:MAIL_PW"), NO_RUNNER);
    }

    // AC1

    @Test
    void abandonedTimeoutIsDerivedFromTheToolDeadline() {
        assertThat(AppComponents.abandonedTimeout(Duration.ofSeconds(90)))
                .isEqualTo(Duration.ofSeconds(120));
        assertThat(AppComponents.abandonedTimeout(Duration.ofMinutes(5)))
                .isEqualTo(Duration.ofMinutes(5).plusSeconds(30));
    }

    @Test
    @SuppressWarnings("unchecked")
    void configuredPoolSettingsReachTheUnderlyingPool() throws Exception {
        AppComponents c = create(config("env:MAIL_PW", "tools:\n  deadline: 5m\npool:\n  maxPerMailbox: 2\n"), NO_RUNNER);
        // StorePool.pool() is package-private to core: read the commons-pool field reflectively
        var field = de.alexanderlindhorst.imapmcp.core.imap.pool.StorePool.class.getDeclaredField("pool");
        field.setAccessible(true);
        var pool = (org.apache.commons.pool2.impl.GenericKeyedObjectPool<?, ?>) field.get(c.pool());
        assertThat(pool.getMaxTotalPerKey()).isEqualTo(2);
        assertThat(pool.getMaxTotal()).isEqualTo(64);
        assertThat(pool.getRemoveAbandonedOnMaintenance()).isTrue();
        // derived from tools.deadline, not the 120 s default (#147 item 9)
        assertThat(pool.getRemoveAbandonedTimeoutDuration())
                .isEqualTo(AppComponents.abandonedTimeout(Duration.ofMinutes(5)))
                .isEqualTo(Duration.ofMinutes(5).plusSeconds(30));
    }

    @Test
    void statefulSpecsAreExactlyTheFiveReadOnlyTools() throws IOException {
        List<McpServerFeatures.SyncToolSpecification> specs = create().statefulToolSpecs();
        assertThat(specs).extracting(s -> s.tool().name()).containsExactlyElementsOf(TOOLS);
        for (var s : specs) {
            McpSchema.ToolAnnotations a = s.tool().annotations();
            assertThat(a.readOnlyHint()).isTrue();
            assertThat(a.destructiveHint()).isFalse();
            assertThat(a.idempotentHint()).isTrue();
            assertThat(a.openWorldHint()).isTrue();
            assertThat(s.tool().outputSchema()).isNotEmpty();
        }
    }

    // AC3

    @Test
    void statelessSpecsExposeIdenticalToolJson() throws IOException {
        AppComponents c = create();
        List<McpStatelessServerFeatures.SyncToolSpecification> stateless = c.statelessToolSpecs(ctx -> LOCAL);
        assertThat(stateless).extracting(McpStatelessServerFeatures.SyncToolSpecification::tool)
                .isEqualTo(c.statefulToolSpecs().stream().map(McpServerFeatures.SyncToolSpecification::tool).toList());
    }

    // AC2

    @Test
    @SuppressWarnings("unchecked")
    void toolsRunEndToEndAgainstGreenMail() throws Exception {
        AppComponents c = create();
        GREEN_MAIL.setUser("user@localhost", "user", "pw").deliver(GreenMailUtil.createTextEmail("user@localhost",
                "from@example.org", "hello", "body text", GREEN_MAIL.getImaps().getServerSetup()));
        var byName = new java.util.HashMap<String, McpServerFeatures.SyncToolSpecification>();
        c.statefulToolSpecs().forEach(s -> byName.put(s.tool().name(), s));
        McpSyncServerExchange ex = mock(McpSyncServerExchange.class);
        when(ex.transportContext()).thenReturn(McpTransportContext.EMPTY);

        Map<String, Object> accounts = call(byName, ex, "list_accounts", Map.of());
        String acct = (String) ((List<Map<String, Object>>) accounts.get("accounts")).get(0).get("account");
        assertThat(acct).startsWith("acc_");

        Map<String, Object> folders = call(byName, ex, "list_folders", Map.of("account", acct));
        assertThat(((List<Map<String, Object>>) folders.get("folders")).stream().map(f -> f.get("folder")))
                .contains("INBOX");

        Map<String, Object> found = call(byName, ex, "search_messages", Map.of("account", acct, "folder", "INBOX"));
        List<Map<String, Object>> msgs = (List<Map<String, Object>>) found.get("messages");
        assertThat(msgs).hasSize(1);
        String handle = (String) msgs.get(0).get("handle");

        Map<String, Object> msg = call(byName, ex, "get_message", Map.of("account", acct, "message", handle));
        assertThat(((Map<String, Object>) ((Map<String, Object>) msg.get("untrusted")).get("body")).get("text"))
                .isEqualTo("body text");
        Map<String, Object> atts = call(byName, ex, "list_attachments", Map.of("account", acct, "message", handle));
        assertThat(atts.get("total")).isEqualTo(0);
    }

    private static Map<String, Object> call(Map<String, McpServerFeatures.SyncToolSpecification> specs,
            McpSyncServerExchange ex, String tool, Map<String, Object> args) {
        McpSchema.CallToolResult r = specs.get(tool).callHandler().apply(ex, new McpSchema.CallToolRequest(tool, args));
        assertThat(r.isError()).as(tool + ": " + r.content()).isNotEqualTo(Boolean.TRUE);
        @SuppressWarnings("unchecked")
        Map<String, Object> out = (Map<String, Object>) r.structuredContent();
        return out;
    }

    // AC5

    @Test
    void invalidSecretRefFailsStartupWithoutConnecting() throws IOException {
        AppConfig c = config("env:UNSET_VARIABLE");
        assertThatThrownBy(() -> create(c, NO_RUNNER)).isInstanceOf(AppStartupException.class)
                .hasMessageContaining("work").message().doesNotContain("pw");
    }

    @Test
    void failingKeyringRunnerFailsStartup() throws IOException {
        AppConfig c = config("keyring:imap-mcp/work");
        CommandRunner failing = (argv, t) -> new CommandRunner.Result(1, new byte[0], 0);
        assertThatThrownBy(() -> create(c, failing)).isInstanceOf(AppStartupException.class)
                .hasMessageContaining("work");
    }

    @Test
    void unresolvableHandleKeyRefFailsStartup() throws IOException {
        AppConfig c = config("env:MAIL_PW", "security:\n  handleKey: env:NO_SUCH_KEY\n");
        assertThatThrownBy(() -> create(c, NO_RUNNER)).isInstanceOf(AppStartupException.class)
                .hasMessageContaining("security.handleKey");
    }

    @Test
    void configuredHandleKeyAndTokenAreUsed() throws IOException {
        String key = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(new byte[32]);
        String token = "t".repeat(40);
        AppConfig c = config("env:MAIL_PW", "security:\n  handleKey: env:HK\nhttp:\n  token: env:TK\n");
        AppComponents a = create(c, NO_RUNNER, Map.of("MAIL_PW", "pw", "HK", key, "TK", token));
        assertThat(new String(a.httpToken())).isEqualTo(token);
        assertThat(Files.exists(dir.resolve("state").resolve("http-token"))).isFalse();
    }

    @Test
    void startupMakesNoImapConnection() throws IOException {
        assertThat(create().pool().createdCount()).isZero();
    }

    // httpToken

    @Test
    void httpTokenComesFromStateDirectoryWhenNotConfigured() throws IOException {
        AppComponents c = create();
        char[] t = c.httpToken();
        assertThat(t.length).isGreaterThanOrEqualTo(32);
        assertThat(c.httpToken()).isEqualTo(t);
        assertThat(Files.exists(dir.resolve("state").resolve("http-token"))).isTrue();
    }

    // AC6

    @Test
    void closeLogsOutIdleStoresAndIsIdempotent() throws Exception {
        AppComponents c = create();
        McpSyncServerExchange ex = mock(McpSyncServerExchange.class);
        when(ex.transportContext()).thenReturn(McpTransportContext.EMPTY);
        McpSchema.CallToolResult r = c.statefulToolSpecs().get(0).callHandler()
                .apply(ex, new McpSchema.CallToolRequest("list_accounts", Map.of()));
        assertThat(r.isError()).isNotEqualTo(Boolean.TRUE);
        assertThat(c.pool().createdCount()).isPositive();
        assertThat(c.pool().destroyedCount()).isZero();
        c.close();
        c.close();
        assertThat(c.pool().destroyedCount()).isEqualTo(c.pool().createdCount());
    }
}
