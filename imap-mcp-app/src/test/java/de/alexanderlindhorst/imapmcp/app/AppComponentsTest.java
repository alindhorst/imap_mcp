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
        return create(c, runner, env, AppComponents.Mode.STDIO);
    }

    private AppComponents create(AppConfig c, CommandRunner runner, Map<String, String> env,
            AppComponents.Mode mode) {
        AppComponents a = AppComponents.create(c, paths(), Clock.systemUTC(), env, p -> {
            p.put("mail.imaps.ssl.trust", "*");
            p.put("mail.imaps.ssl.checkserveridentity", "false");
            return p;
        }, Platform.LINUX, runner, platform -> true, mode);
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

    // v3a download_attachment registration (#288)

    private AppConfig withDownload(boolean readOnly) throws IOException {
        Path dl = Files.createDirectories(dir.resolve("dl-" + readOnly));
        Files.setPosixFilePermissions(dl, java.nio.file.attribute.PosixFilePermissions.fromString("rwx------"));
        return config("env:MAIL_PW", "tools:\n  download:\n    dir: " + dl + "\n", readOnly);
    }

    @Test
    void downloadAttachmentIsRegisteredOnlyWithADownloadDirectory() throws IOException {
        GREEN_MAIL.setUser("user@localhost", "user", "pw");
        List<String> all = new ArrayList<>(TOOLS);
        all.addAll(WRITE_TOOLS);
        List<String> with = new ArrayList<>(all);
        with.add("download_attachment");

        AppComponents c = create(withDownload(false), NO_RUNNER);

        assertThat(c.statefulToolSpecs()).extracting(s -> s.tool().name()).containsExactlyElementsOf(with);
        assertThat(c.statelessToolSpecs(ctx -> LOCAL)).extracting(s -> s.tool().name())
                .containsExactlyElementsOf(with);
        assertThat(create(config("env:MAIL_PW"), NO_RUNNER).statefulToolSpecs()).extracting(s -> s.tool().name())
                .containsExactlyElementsOf(all);
    }

    @Test
    void downloadAttachmentIsRegisteredWhenEveryAccountIsReadOnly() throws IOException {
        GREEN_MAIL.setUser("user@localhost", "user", "pw");
        List<String> expected = new ArrayList<>(TOOLS);
        expected.add("download_attachment");

        assertThat(create(withDownload(true), NO_RUNNER).statefulToolSpecs()).extracting(s -> s.tool().name())
                .containsExactlyElementsOf(expected);
    }

    @Test
    void downloadAttachmentAnnotationsSchemaAndDescriptionReachTheMcpLayer() throws IOException {
        GREEN_MAIL.setUser("user@localhost", "user", "pw");
        AppComponents c = create(withDownload(false), NO_RUNNER);
        var spec = c.statefulToolSpecs().stream().filter(s -> s.tool().name().equals("download_attachment"))
                .findFirst().orElseThrow();

        McpSchema.ToolAnnotations a = spec.tool().annotations();
        assertThat(a).extracting(McpSchema.ToolAnnotations::readOnlyHint, McpSchema.ToolAnnotations::destructiveHint,
                McpSchema.ToolAnnotations::idempotentHint, McpSchema.ToolAnnotations::openWorldHint)
                .containsExactly(false, false, false, true);
        assertThat(spec.tool().outputSchema()).isNotEmpty();
        assertThat(c.definitions()).allSatisfy(d ->
                assertThat(de.alexanderlindhorst.imapmcp.core.tool.DescriptionRules.violations(d)).as(d.name()).isEmpty());
    }

    @Test
    @SuppressWarnings("unchecked")
    void downloadAttachmentRunsEndToEndAndIsRecognisedByTheExecutor() throws Exception {
        GREEN_MAIL.setUser("user@localhost", "user", "pw");
        AppConfig cfg = withDownload(false);
        AppComponents c = create(cfg, NO_RUNNER);
        jakarta.mail.internet.MimeMultipart mp = new jakarta.mail.internet.MimeMultipart("mixed");
        jakarta.mail.internet.MimeBodyPart text = new jakarta.mail.internet.MimeBodyPart();
        text.setText("see attached");
        mp.addBodyPart(text);
        jakarta.mail.internet.MimeBodyPart att = new jakarta.mail.internet.MimeBodyPart();
        att.setDataHandler(new jakarta.activation.DataHandler(new jakarta.mail.util.ByteArrayDataSource(
                "attachment bytes".getBytes(java.nio.charset.StandardCharsets.UTF_8), "application/pdf")));
        att.setFileName("doc.pdf");
        att.setDisposition("attachment");
        mp.addBodyPart(att);
        jakarta.mail.internet.MimeMessage m = new jakarta.mail.internet.MimeMessage(
                jakarta.mail.Session.getInstance(new java.util.Properties()));
        m.setFrom("from@example.org");
        m.setRecipients(jakarta.mail.Message.RecipientType.TO, "user@localhost");
        m.setSubject("with attachment");
        m.setContent(mp);
        m.saveChanges();
        GREEN_MAIL.setUser("user@localhost", "user", "pw").deliver(m);
        var byName = new java.util.HashMap<String, McpServerFeatures.SyncToolSpecification>();
        c.statefulToolSpecs().forEach(s -> byName.put(s.tool().name(), s));
        McpSyncServerExchange ex = mock(McpSyncServerExchange.class);
        when(ex.transportContext()).thenReturn(McpTransportContext.EMPTY);
        Map<String, Object> accounts = call(byName, ex, "list_accounts", Map.of());
        String acct = (String) ((List<Map<String, Object>>) accounts.get("accounts")).get(0).get("account");
        Map<String, Object> found = call(byName, ex, "search_messages", Map.of("account", acct, "folder", "INBOX"));
        String handle = (String) ((List<Map<String, Object>>) found.get("messages")).get(0).get("handle");

        Map<String, Object> out = call(byName, ex, "download_attachment",
                Map.of("account", acct, "message", handle, "part", "2"));

        assertThat(out).containsEntry("bytes", 16L).containsEntry("delivery", "file");
        assertThat(Files.readString(cfg.tools().download().dir().resolve("doc.pdf"))).isEqualTo("attachment bytes");
        Map<String, Object> bad = new java.util.HashMap<>(Map.of("account", acct, "message", handle, "part", "0"));
        McpSchema.CallToolResult r = byName.get("download_attachment").callHandler()
                .apply(ex, new McpSchema.CallToolRequest("download_attachment", bad));
        assertThat(r.isError()).isTrue();
    }

    // v3a purge gate (#282)

    private static final String PURGE = "    allowPurge: true\n";

    @Test
    void purgeIsRegisteredOnlyInStdioAndOnlyWithAPurgeAccount() throws IOException {
        AppConfig plain = config("env:MAIL_PW");
        AppConfig purge = config("env:MAIL_PW", PURGE);
        assertThat(AppComponents.purgeRegistered(plain, AppComponents.Mode.STDIO)).isFalse();
        assertThat(AppComponents.purgeRegistered(purge, AppComponents.Mode.STDIO)).isTrue();
        assertThat(AppComponents.purgeRegistered(purge, AppComponents.Mode.HTTP_LOCAL)).isFalse();
        assertThat(create(purge, NO_RUNNER).purgeRegistered()).isTrue();
        assertThat(create(plain, NO_RUNNER).purgeRegistered()).isFalse();
        assertThat(create(purge, NO_RUNNER, Map.of("MAIL_PW", "pw"), AppComponents.Mode.HTTP_LOCAL)
                .purgeRegistered()).isFalse();
    }

    @Test
    void purgeToolIsRegisteredInStdioWithAPurgeAccountAndNotInHttpLocalOrWithout() throws IOException {
        GREEN_MAIL.setUser("user@localhost", "user", "pw");
        AppConfig purge = config("env:MAIL_PW", PURGE);
        List<String> all = new ArrayList<>(TOOLS);
        all.addAll(WRITE_TOOLS);
        List<String> withPurge = new ArrayList<>(all);
        withPurge.add("purge_messages");
        assertThat(create(purge, NO_RUNNER).statefulToolSpecs()).extracting(s -> s.tool().name())
                .containsExactlyElementsOf(withPurge);
        assertThat(create(purge, NO_RUNNER, Map.of("MAIL_PW", "pw"), AppComponents.Mode.HTTP_LOCAL)
                .statefulToolSpecs()).extracting(s -> s.tool().name()).containsExactlyElementsOf(all);
        assertThat(create(config("env:MAIL_PW"), NO_RUNNER).statefulToolSpecs()).extracting(s -> s.tool().name())
                .containsExactlyElementsOf(all);
    }

    @Test
    void purgeToolAnnotationsAndSchemasReachTheMcpLayer() throws IOException {
        GREEN_MAIL.setUser("user@localhost", "user", "pw");
        var spec = create(config("env:MAIL_PW", PURGE), NO_RUNNER).statefulToolSpecs().stream()
                .filter(s -> s.tool().name().equals("purge_messages")).findFirst().orElseThrow();
        McpSchema.ToolAnnotations a = spec.tool().annotations();
        assertThat(a).extracting(McpSchema.ToolAnnotations::readOnlyHint, McpSchema.ToolAnnotations::destructiveHint,
                McpSchema.ToolAnnotations::idempotentHint, McpSchema.ToolAnnotations::openWorldHint)
                .containsExactly(false, true, true, true);
        assertThat(spec.tool().outputSchema()).isNotEmpty();
        assertThat(spec.tool().description()).contains("This cannot be undone.");
    }

    @Test
    void stdioWithPurgeRequiresADeadlineOfAtLeast60Seconds() throws IOException {
        AppConfig short45 = config("env:MAIL_PW", PURGE + "tools:\n  deadline: 45s\n");
        assertThatThrownBy(() -> create(short45, NO_RUNNER)).isInstanceOf(AppStartupException.class)
                .hasMessageContaining("tools.deadline").hasMessageContaining("60s");
        assertThat(create(config("env:MAIL_PW", PURGE + "tools:\n  deadline: 60s\n"), NO_RUNNER)
                .purgeRegistered()).isTrue();
        // not registered: the 30 s write guard of the loader is all that applies
        assertThat(create(short45, NO_RUNNER, Map.of("MAIL_PW", "pw"), AppComponents.Mode.HTTP_LOCAL)
                .purgeRegistered()).isFalse();
        assertThat(create(config("env:MAIL_PW", "tools:\n  deadline: 45s\n"), NO_RUNNER).purgeRegistered())
                .isFalse();
    }

    @Test
    void startupLogsPurgeRegistrationAndAWarnInHttpLocal() throws IOException {
        ch.qos.logback.classic.Logger log =
                (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger("imapmcp.app");
        var appender = new ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent>();
        appender.start();
        log.addAppender(appender);
        try {
            AppConfig purge = config("env:MAIL_PW", PURGE);
            create(purge, NO_RUNNER);
            assertThat(appender.list).filteredOn(e -> e.getLevel() == ch.qos.logback.classic.Level.INFO)
                    .extracting(ch.qos.logback.classic.spi.ILoggingEvent::getFormattedMessage)
                    .contains("purge_messages registered=true, accounts with allowPurge=1 of 1");
            appender.list.clear();
            create(purge, NO_RUNNER, Map.of("MAIL_PW", "pw"), AppComponents.Mode.HTTP_LOCAL);
            assertThat(appender.list).extracting(ch.qos.logback.classic.spi.ILoggingEvent::getFormattedMessage)
                    .contains("purge_messages registered=false, accounts with allowPurge=1 of 1");
            assertThat(appender.list).filteredOn(e -> e.getLevel() == ch.qos.logback.classic.Level.WARN)
                    .hasSize(1).extracting(ch.qos.logback.classic.spi.ILoggingEvent::getFormattedMessage)
                    .allSatisfy(m -> assertThat(m).contains("allowPurge").contains("http-local")
                            .contains("not available").doesNotContain("work"));
            appender.list.clear();
            create(config("env:MAIL_PW"), NO_RUNNER);
            assertThat(appender.list).extracting(ch.qos.logback.classic.spi.ILoggingEvent::getFormattedMessage)
                    .contains("purge_messages registered=false, accounts with allowPurge=0 of 1")
                    .noneMatch(m -> m.contains("http-local"));
        } finally {
            log.detachAppender(appender);
        }
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
        return startupLines(create, "write tools");
    }

    private List<String> startupLines(Runnable create, String contains) {
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
                .filter(m -> m.contains(contains)).toList();
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

    // v3a T6: download directory (SPEC 5.3)

    private List<String> infoLines(String contains, Runnable create) {
        return startupLines(create, contains);
    }

    private AppConfig downloadConfig(Path downloadDir) throws IOException {
        return config("env:MAIL_PW", "tools:\n  download:\n    dir: " + downloadDir + "\n    maxBytes: 5MiB\n");
    }

    @Test
    void withoutDownloadDirTheToolDirectoryIsAbsentAndOneInfoLineSaysNo() throws IOException {
        GREEN_MAIL.setUser("user@localhost", "user", "pw");
        AppConfig c = config("env:MAIL_PW");
        AppComponents[] holder = new AppComponents[1];

        assertThat(infoLines("download_attachment", () -> holder[0] = create(c, NO_RUNNER)))
                .containsExactly("download_attachment registered=false");
        assertThat(holder[0].downloadDirectory()).isNull();
    }

    @Test
    void withDownloadDirTheCheckedDirectoryIsHeldAndTheInfoLineHasNoPath() throws IOException {
        GREEN_MAIL.setUser("user@localhost", "user", "pw");
        Path dl = Files.createDirectory(dir.resolve("dl"));
        java.nio.file.Files.setPosixFilePermissions(dl,
                java.nio.file.attribute.PosixFilePermissions.fromString("rwx------"));
        AppConfig c = downloadConfig(dl);
        AppComponents[] holder = new AppComponents[1];

        assertThat(infoLines("download_attachment", () -> holder[0] = create(c, NO_RUNNER)))
                .containsExactly("download_attachment registered=true");
        assertThat(holder[0].downloadDirectory().path()).isEqualTo(dl.toRealPath());
        assertThat(holder[0].downloadDirectory().maxBytes()).isEqualTo(5L * 1024 * 1024);
    }

    @Test
    void aBadDownloadDirFailsStartupNamingTheKey() throws IOException {
        GREEN_MAIL.setUser("user@localhost", "user", "pw");
        AppConfig c = downloadConfig(dir.resolve("missing"));

        assertThatThrownBy(() -> create(c, NO_RUNNER)).isInstanceOf(AppStartupException.class)
                .hasMessageContaining("tools.download.dir");
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
            cx.tryBeginMutation();
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
                        t.sinceMaxCandidates(), t.download()), base.secrets(), base.http(), base.security(), base.mode(),
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
