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
    private static final List<String> WRITE_TOOLS =
            List.of("set_flags", "move_messages", "copy_messages", "delete_messages", "create_folder");
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
        return config(password, extra, false);
    }

    private AppConfig config(String password, String extra, boolean readOnly) throws IOException {
        Path f = dir.resolve("config.yaml");
        Files.writeString(f, """
                accounts:
                  - id: work
                    host: 127.0.0.1
                    port: %d
                    username: user
                    password: "%s"
                """.formatted(GREEN_MAIL.getImaps().getPort(), password) + (readOnly ? "    readOnly: true\n" : "")
                + extra);
        return ConfigLoader.load(f, Map.of());
    }

    /** One read-only account (ro) and one writable account (rw), both against GreenMail. */
    private AppConfig mixedConfig() throws IOException {
        Path f = dir.resolve("config.yaml");
        Files.writeString(f, """
                accounts:
                  - id: ro
                    host: 127.0.0.1
                    port: %1$d
                    username: user
                    password: "env:MAIL_PW"
                    readOnly: true
                  - id: rw
                    host: 127.0.0.1
                    port: %1$d
                    username: user
                    password: "env:MAIL_PW"
                """.formatted(GREEN_MAIL.getImaps().getPort()));
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
    void allAccountsReadOnlyRegistersExactlyTheFiveV1Tools() throws IOException {
        GREEN_MAIL.setUser("user@localhost", "user", "pw");
        List<McpServerFeatures.SyncToolSpecification> specs =
                create(config("env:MAIL_PW", "", true), NO_RUNNER).statefulToolSpecs();
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

    @Test
    void defaultConfigRegistersTheTenTools() throws IOException {
        AppComponents c = create();
        List<String> all = new ArrayList<>(TOOLS);
        all.addAll(WRITE_TOOLS);
        assertThat(c.statefulToolSpecs()).extracting(s -> s.tool().name()).containsExactlyElementsOf(all);
        assertThat(c.statelessToolSpecs(ctx -> LOCAL)).extracting(s -> s.tool().name())
                .containsExactlyElementsOf(all);
    }

    @Test
    void writeToolAnnotationsReachTheMcpLayer() throws IOException {
        Map<String, McpSchema.ToolAnnotations> byName = new java.util.HashMap<>();
        create().statefulToolSpecs().forEach(s -> byName.put(s.tool().name(), s.tool().annotations()));
        for (String t : List.of("set_flags", "move_messages", "delete_messages")) {
            assertThat(byName.get(t)).as(t).extracting(McpSchema.ToolAnnotations::readOnlyHint,
                    McpSchema.ToolAnnotations::destructiveHint, McpSchema.ToolAnnotations::idempotentHint,
                    McpSchema.ToolAnnotations::openWorldHint).containsExactly(false, true, true, true);
        }
        assertThat(byName.get("copy_messages")).extracting(McpSchema.ToolAnnotations::readOnlyHint,
                McpSchema.ToolAnnotations::destructiveHint, McpSchema.ToolAnnotations::idempotentHint,
                McpSchema.ToolAnnotations::openWorldHint).containsExactly(false, false, false, true);
        assertThat(byName.get("create_folder")).extracting(McpSchema.ToolAnnotations::readOnlyHint,
                McpSchema.ToolAnnotations::destructiveHint, McpSchema.ToolAnnotations::idempotentHint,
                McpSchema.ToolAnnotations::openWorldHint).containsExactly(false, false, true, true);
    }

    /** #207: SPEC v0.8 has no write-tools switch; no tool description or schema may mention one. */
    @Test
    void noRegisteredToolMentionsAWriteToolsSwitch() throws IOException {
        for (var s : create().statefulToolSpecs()) {
            assertThat(s.tool().description()).as(s.tool().name()).doesNotContain("writeTools");
            assertThat(String.valueOf(s.tool().inputSchema())).as(s.tool().name()).doesNotContain("writeTools");
            assertThat(String.valueOf(s.tool().outputSchema())).as(s.tool().name()).doesNotContain("writeTools");
        }
    }

    @Test
    void descriptionRulesHoldForAllTenRegisteredTools() throws IOException {
        GREEN_MAIL.setUser("user@localhost", "user", "pw");
        AppConfig cfg = config("env:MAIL_PW");
        List<de.alexanderlindhorst.imapmcp.core.tool.ToolDefinition> defs = create(cfg, NO_RUNNER).definitions();
        assertThat(defs).hasSize(10);
        for (var d : defs) {
            assertThat(de.alexanderlindhorst.imapmcp.core.tool.DescriptionRules.violations(d)).as(d.name()).isEmpty();
        }
    }

    @Test
    @SuppressWarnings("unchecked")
    void mixedConfigRegistersTenToolsAndDeniesWritesOnTheReadOnlyAccount() throws Exception {
        GREEN_MAIL.setUser("user@localhost", "user", "pw");
        AppComponents c = create(mixedConfig(), NO_RUNNER);
        var byName = new java.util.HashMap<String, McpServerFeatures.SyncToolSpecification>();
        c.statefulToolSpecs().forEach(s -> byName.put(s.tool().name(), s));
        assertThat(byName).hasSize(10);
        McpSyncServerExchange ex = mock(McpSyncServerExchange.class);
        when(ex.transportContext()).thenReturn(McpTransportContext.EMPTY);
        List<Map<String, Object>> accounts =
                (List<Map<String, Object>>) call(byName, ex, "list_accounts", Map.of()).get("accounts");
        String ro = (String) accounts.stream().filter(a -> Boolean.TRUE.equals(a.get("read_only"))).findFirst()
                .orElseThrow().get("account");
        McpSchema.CallToolResult r = byName.get("set_flags").callHandler().apply(ex,
                new McpSchema.CallToolRequest("set_flags", Map.of("account", ro, "handles", List.of("x"), "seen", true)));
        assertThat(r.isError()).isTrue();
        Map<String, Object> err = ErrorResults.error(r);
        assertThat(err.get("code")).isEqualTo("PERMISSION_DENIED");
        assertThat(err.toString()).contains("account_read_only");
    }

    // startup INFO line (SPEC 5.3): counts only

    private List<String> startupLines(Runnable create) {
        ch.qos.logback.classic.Logger logger =
                (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger("imapmcp.app");
        var appender = new ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent>();
        appender.start();
        logger.addAppender(appender);
        try {
            create.run();
        } finally {
            logger.detachAppender(appender);
        }
        return appender.list.stream().filter(e -> e.getLevel() == ch.qos.logback.classic.Level.INFO)
                .map(ch.qos.logback.classic.spi.ILoggingEvent::getFormattedMessage)
                .filter(m -> m.contains("write tools")).toList();
    }

    @Test
    void startupLogsWhetherWriteToolsAreRegisteredWithCountsOnly() throws IOException {
        GREEN_MAIL.setUser("user@localhost", "user", "pw");
        AppConfig writable = config("env:MAIL_PW");
        assertThat(startupLines(() -> create(writable, NO_RUNNER)))
                .containsExactly("write tools registered=true, read-only accounts=0 of 1");
        AppConfig mixed = mixedConfig();
        assertThat(startupLines(() -> create(mixed, NO_RUNNER)))
                .containsExactly("write tools registered=true, read-only accounts=1 of 2");
        AppConfig readOnly = config("env:MAIL_PW", "", true);
        assertThat(startupLines(() -> create(readOnly, NO_RUNNER)))
                .containsExactly("write tools registered=false, read-only accounts=1 of 1");
    }

    // #195 comment: executor-produced outcomes of write tools get one write-call INFO line

    private List<String> writeCallLines(Runnable action) {
        ch.qos.logback.classic.Logger logger = (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory
                .getLogger("de.alexanderlindhorst.imapmcp.core.tools.write");
        var appender = new ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent>();
        appender.start();
        logger.addAppender(appender);
        try {
            action.run();
        } finally {
            logger.detachAppender(appender);
        }
        return appender.list.stream().filter(e -> e.getLevel() == ch.qos.logback.classic.Level.INFO)
                .map(ch.qos.logback.classic.spi.ILoggingEvent::getFormattedMessage).toList();
    }

    private static de.alexanderlindhorst.imapmcp.core.tool.ToolHandler sleepingAfterMark() {
        return (cx, a) -> {
            cx.markMutationStarted();
            try {
                Thread.sleep(10_000);
            } catch (InterruptedException e) {
                // interrupted by the executor
            }
            return new de.alexanderlindhorst.imapmcp.core.tool.ToolOutcome.Success(Map.of());
        };
    }

    @Test
    void executorProducedWriteOutcomesLogOneWriteCallLineAndReadToolsNone() throws IOException {
        GREEN_MAIL.setUser("user@localhost", "user", "pw");
        AppConfig base = config("env:MAIL_PW");
        var t = base.tools();
        AppConfig shortDeadline = new AppConfig(base.accounts(), base.imap(), base.pool(),
                new AppConfig.Tools(Duration.ofMillis(100), t.maxCharsCap(), t.maxBodyPartBytes(), t.maxFolders(),
                        t.sinceMaxCandidates()), base.secrets(), base.http(), base.security(), base.mode(),
                base.logLevel());
        var ex = create(shortDeadline, NO_RUNNER).executor();
        var ctx = new de.alexanderlindhorst.imapmcp.core.tool.ToolCallContext(LOCAL, "req-9");
        List<String> lines = writeCallLines(() -> {
            ex.wrap(de.alexanderlindhorst.imapmcp.core.tools.write.MoveMessagesTool.DEFINITION, sleepingAfterMark())
                    .handle(ctx, Map.of());
            ex.wrap(de.alexanderlindhorst.imapmcp.core.tools.write.CopyMessagesTool.DEFINITION, sleepingAfterMark())
                    .handle(new de.alexanderlindhorst.imapmcp.core.tool.ToolCallContext(LOCAL, "req-10"), Map.of());
            ex.wrap(de.alexanderlindhorst.imapmcp.core.tools.GetMessageTool.definition(1000), sleepingAfterMark())
                    .handle(new de.alexanderlindhorst.imapmcp.core.tool.ToolCallContext(LOCAL, "req-11"), Map.of());
        });
        assertThat(lines).hasSize(2);
        assertThat(lines.get(0)).contains("tool=move_messages", "request_id=req-9", "error_code=TIMEOUT");
        assertThat(lines.get(1)).contains("tool=copy_messages", "request_id=req-10", "error_code=OUTCOME_UNKNOWN");
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
