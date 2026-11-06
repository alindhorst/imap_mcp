package de.alexanderlindhorst.imapmcp.core.tools;

import static de.alexanderlindhorst.imapmcp.core.tools.DownloadAttachmentToolTest.attachment;
import static de.alexanderlindhorst.imapmcp.core.tools.DownloadAttachmentToolTest.mixed;
import static de.alexanderlindhorst.imapmcp.core.tools.DownloadAttachmentToolTest.random;
import static de.alexanderlindhorst.imapmcp.core.tools.DownloadAttachmentToolTest.sha;
import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.icegreen.greenmail.junit5.GreenMailExtension;
import com.icegreen.greenmail.util.ServerSetupTest;
import de.alexanderlindhorst.imapmcp.core.account.InMemoryAccountRepository;
import de.alexanderlindhorst.imapmcp.core.account.MailAccount;
import de.alexanderlindhorst.imapmcp.core.account.MailAccount.Flavor;
import de.alexanderlindhorst.imapmcp.core.account.MailAccount.Security;
import de.alexanderlindhorst.imapmcp.core.account.Secret;
import de.alexanderlindhorst.imapmcp.core.account.SecretRef;
import de.alexanderlindhorst.imapmcp.core.crypto.KeyRing;
import de.alexanderlindhorst.imapmcp.core.crypto.LogIds;
import de.alexanderlindhorst.imapmcp.core.download.InlineLimiter;
import de.alexanderlindhorst.imapmcp.core.handle.MessageHandles;
import de.alexanderlindhorst.imapmcp.core.handle.MessageRef;
import de.alexanderlindhorst.imapmcp.core.imap.CapabilityCache;
import de.alexanderlindhorst.imapmcp.core.imap.ImapSessionFactory;
import de.alexanderlindhorst.imapmcp.core.imap.ImapTimeouts;
import de.alexanderlindhorst.imapmcp.core.imap.pool.PoolSettings;
import de.alexanderlindhorst.imapmcp.core.imap.pool.StoreLeases;
import de.alexanderlindhorst.imapmcp.core.imap.pool.StorePool;
import de.alexanderlindhorst.imapmcp.core.testsupport.FaultingImapProxy;
import de.alexanderlindhorst.imapmcp.core.tenancy.AccountContext;
import de.alexanderlindhorst.imapmcp.core.tenancy.AccountId;
import de.alexanderlindhorst.imapmcp.core.tenancy.TenantId;
import de.alexanderlindhorst.imapmcp.core.tool.DescriptionRules;
import de.alexanderlindhorst.imapmcp.core.tool.ToolCallContext;
import de.alexanderlindhorst.imapmcp.core.tool.ToolExecutor;
import de.alexanderlindhorst.imapmcp.core.tool.ToolOutcome;
import jakarta.mail.Flags;
import jakarta.mail.Folder;
import jakarta.mail.Message;
import jakarta.mail.Store;
import jakarta.mail.internet.MimeMessage;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.eclipse.angus.mail.imap.IMAPFolder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.slf4j.LoggerFactory;

/** SPEC 4 v3b: download_attachment in http-public (embedded resource, in-memory sink). */
class EmbeddedDownloadAttachmentToolTest {

    static final TenantId A = new TenantId("tenant-a");
    static final TenantId B = new TenantId("tenant-b");
    static final AccountId ACCT = new AccountId("acct");
    static final KeyRing KEYS = new KeyRing(1, Map.of(1, new byte[32]));

    @RegisterExtension
    static final GreenMailExtension GREEN_MAIL = new GreenMailExtension(ServerSetupTest.IMAPS.dynamicPort());

    final MessageHandles handles = new MessageHandles(KEYS);
    final ListAppender<ILoggingEvent> trace = new ListAppender<>();
    Logger traceLogger;
    ImapSessionFactory sessions;
    StorePool pool;
    FaultingImapProxy proxy;
    DownloadAttachmentTool tool;
    String acctHandle;
    long maxBytes = 5 * 1024 * 1024;
    InlineLimiter limiter = new InlineLimiter(4);

    @BeforeEach
    void setUp() throws Exception {
        GREEN_MAIL.setUser("user@localhost", "user", "secret");
        proxy = new FaultingImapProxy(GREEN_MAIL.getImaps().getPort());
        sessions = new ImapSessionFactory(ImapTimeouts.DEFAULTS, p -> {
            p.put("mail.imaps.ssl.trust", "*");
            p.put("mail.imaps.ssl.checkserveridentity", "false");
            return p;
        }, true);
        traceLogger = (Logger) LoggerFactory.getLogger("imapmcp.imap.trace");
        traceLogger.setLevel(Level.DEBUG);
        traceLogger.addAppender(trace);
        trace.start();
        build(null);
    }

    void build(DownloadAttachmentTool.StepHook hook) {
        if (pool != null) {
            pool.close();
        }
        MailAccount acct = new MailAccount(ACCT, "acct", null, "127.0.0.1", proxy.port(), Security.IMPLICIT_TLS,
                Flavor.GENERIC, "user", new SecretRef("env:x"), true);
        InMemoryAccountRepository repo = new InMemoryAccountRepository(Map.of(A, List.of(acct), B, List.of(acct)));
        pool = new StorePool(sessions, (t, a) -> Optional.of(new Secret("secret".toCharArray())), new CapabilityCache(),
                k -> acct, PoolSettings.DEFAULTS);
        StoreLeases leases = new StoreLeases(pool, Duration.ofSeconds(10));
        AccountArgument accounts = new AccountArgument(repo);
        MessageLocator locator = new MessageLocator(handles);
        DownloadAttachmentTool.InlineSettings settings = new DownloadAttachmentTool.InlineSettings(maxBytes);
        tool = hook == null ? DownloadAttachmentTool.embedded(accounts, locator, leases, settings, limiter)
                : DownloadAttachmentTool.embedded(accounts, locator, leases, settings, limiter, hook);
        acctHandle = InMemoryAccountRepository.handle(A, ACCT);
    }

    @AfterEach
    void tearDown() throws Exception {
        traceLogger.detachAppender(trace);
        pool.close();
        sessions.close();
        proxy.close();
    }

    Store client() throws Exception {
        java.util.Properties props = new java.util.Properties();
        props.put("mail.imaps.ssl.trust", "*");
        props.put("mail.imaps.ssl.checkserveridentity", "false");
        Store s = jakarta.mail.Session.getInstance(props).getStore("imaps");
        s.connect("127.0.0.1", GREEN_MAIL.getImaps().getPort(), "user", "secret");
        return s;
    }

    String deliver(MimeMessage msg) throws Exception {
        try (Store s = client()) {
            IMAPFolder f = (IMAPFolder) s.getFolder("INBOX");
            f.open(Folder.READ_WRITE);
            f.appendMessages(new Message[] {msg});
            Message last = f.getMessage(f.getMessageCount());
            MessageRef ref = new MessageRef("INBOX", f.getUIDValidity(), f.getUID(last));
            f.close(false);
            return handles.encode(A, ACCT, ref);
        }
    }

    ToolOutcome call(TenantId tenant, String account, String message, String part) {
        return tool.handle(new ToolCallContext(new AccountContext(tenant), "rid"),
                Map.of("account", account, "message", message, "part", part));
    }

    ToolOutcome call(String message, String part) {
        return call(A, acctHandle, message, part);
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> ok(ToolOutcome o) {
        assertThat(o).isInstanceOf(ToolOutcome.Success.class);
        return (Map<String, Object>) ((ToolOutcome.Success) o).structured();
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> error(ToolOutcome o) {
        assertThat(o).isInstanceOf(ToolOutcome.Failure.class);
        return (Map<String, Object>) ((ToolOutcome.Failure) o).error().toMap().get("error");
    }

    List<String> traceLines() {
        return new ArrayList<>(trace.list.stream().map(ILoggingEvent::getFormattedMessage).toList());
    }

    @Test
    void anAttachmentComesBackAsTextBlockStructureAndOneEmbeddedResource() throws Exception {
        byte[] data = random(5000);
        String h = deliver(mixed(attachment("application/pdf", "doc.pdf", data)));
        ToolCallContext ctx = new ToolCallContext(new AccountContext(A), "rid");

        ToolOutcome o = tool.handle(ctx, Map.of("account", acctHandle, "message", h, "part", "2"));

        Map<String, Object> out = ok(o);
        assertThat(out).containsEntry("delivery", "embedded").containsEntry("directory", null)
                .containsEntry("bytes", 5000L).containsEntry("sha256", sha(data)).containsEntry("renamed", false)
                .containsEntry("part", "2").containsEntry("handle", h);
        @SuppressWarnings("unchecked")
        Map<String, Object> untrusted = (Map<String, Object>) out.get("untrusted");
        assertThat(untrusted).containsEntry("filename", "doc.pdf").containsEntry("content_type", "application/pdf")
                .containsEntry("saved_as", null).containsEntry("path", null);
        List<ToolOutcome.ContentBlock> extra = ((ToolOutcome.Success) o).extraBlocks();
        assertThat(extra).hasSize(1);
        ToolOutcome.EmbeddedResource res = (ToolOutcome.EmbeddedResource) extra.get(0);
        assertThat(res.uri()).isEqualTo("imap-mcp://attachment/" + sha(data));
        assertThat(res.mimeType()).isEqualTo("application/pdf");
        assertThat(java.util.Base64.getDecoder().decode(res.base64())).isEqualTo(data);
        assertThat(ctx.mutationStarted()).as("nothing is written, so no mutation mark").isFalse();
    }

    @Test
    void anUnlistedContentTypeIsOctetStream() throws Exception {
        String h = deliver(mixed(attachment("application/x-msdownload", "a.exe", random(100))));

        ToolOutcome o = call(h, "2");

        ToolOutcome.EmbeddedResource res = (ToolOutcome.EmbeddedResource) ((ToolOutcome.Success) o).extraBlocks().get(0);
        assertThat(res.mimeType()).isEqualTo("application/octet-stream");
        @SuppressWarnings("unchecked")
        Map<String, Object> untrusted = (Map<String, Object>) ok(o).get("untrusted");
        assertThat(untrusted).containsEntry("content_type", "application/x-msdownload");
    }

    @Test
    void tooLargeByTheEstimateIsRefusedBeforeAnyBodyFetch() throws Exception {
        maxBytes = 1000;
        build(null);
        String h = deliver(mixed(attachment("application/pdf", "big.pdf", random(5000))));
        trace.list.clear();

        Map<String, Object> e = error(call(h, "2"));

        assertThat(e).containsEntry("code", "TOO_LARGE");
        @SuppressWarnings("unchecked")
        Map<String, Object> d = (Map<String, Object>) e.get("details");
        assertThat(d).containsEntry("max", 1000L);
        assertThat(traceLines()).noneMatch(l -> l.contains("BODY.PEEK["));
    }

    @Test
    void tooLargeWhileStreamingWhenTheServerSendsMoreThanBodystructureClaims() throws Exception {
        maxBytes = 4000;
        build(null);
        String h = deliver(mixed(attachment("application/pdf", "small.pdf", random(100))));
        proxy.serveBody("BODY.PEEK", java.util.Base64.getMimeEncoder().encode(random(9000)));

        Map<String, Object> e = error(call(h, "2"));

        assertThat(e).containsEntry("code", "TOO_LARGE");
        assertThat((Map<?, ?>) e.get("details")).isEqualTo(Map.of("max", 4000L));
    }

    @Test
    void aPartBelowTheCapIsDelivered() throws Exception {
        maxBytes = 3000;
        build(null);
        String h = deliver(mixed(attachment("image/png", "p.png", random(2800))));

        assertThat(ok(call(h, "2"))).containsEntry("bytes", 2800L);
    }

    @Test
    void moreThanTheConcurrencyCapIsServerBusyAndThePermitIsFreedAfterwards() throws Exception {
        limiter = new InlineLimiter(1);
        CountDownLatch inside = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        build((step, ctx) -> {
            if (step == DownloadAttachmentTool.Step.AFTER_SIZE_CHECK) {
                inside.countDown();
                try {
                    release.await(10, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
        });
        String h = deliver(mixed(attachment("application/pdf", "a.pdf", random(100))));
        ExecutorService ex = Executors.newSingleThreadExecutor();
        try {
            Future<ToolOutcome> first = ex.submit(() -> call(h, "2"));
            assertThat(inside.await(10, TimeUnit.SECONDS)).isTrue();

            Map<String, Object> busy = error(call(h, "2"));
            assertThat(busy).containsEntry("code", "SERVER_BUSY");

            release.countDown();
            assertThat(ok(first.get(10, TimeUnit.SECONDS))).containsEntry("delivery", "embedded");
            assertThat(ok(call(h, "2"))).containsEntry("delivery", "embedded");
        } finally {
            release.countDown();
            ex.shutdownNow();
        }
    }

    @Test
    void thePermitIsFreedAlsoWhenTheCallFails() throws Exception {
        limiter = new InlineLimiter(1);
        build(null);
        String h = deliver(mixed(attachment("application/pdf", "a.pdf", random(100))));
        for (int i = 0; i < 3; i++) {
            assertThat(error(call(h, "9"))).containsEntry("code", "INVALID_ARGUMENT");
        }
        assertThat(ok(call(h, "2"))).containsEntry("delivery", "embedded");
    }

    @Test
    void fetchesOnlyPeekAndLeavesFlagsUnchanged() throws Exception {
        String h = deliver(mixed(attachment("application/pdf", "a.pdf", random(3000))));
        Flags before = flagsOfLast();
        trace.list.clear();

        ok(call(h, "2"));

        List<String> lines = traceLines();
        assertThat(lines).anyMatch(l -> l.contains("EXAMINE"));
        assertThat(lines).anyMatch(l -> l.matches(".*BODY\\.PEEK\\[2\\]<0\\.1048576>.*"));
        assertThat(lines.stream().filter(l -> l.matches("[A-Z]+\\d+ .*")).toList()).noneMatch(
                l -> l.contains("BODY[") || l.contains("RFC822") || l.contains("BINARY") || l.contains(" SELECT ")
                        || l.contains(" STORE ") || l.contains("EXPUNGE"));
        assertThat(flagsOfLast()).isEqualTo(before);
        assertThat(flagsOfLast().contains(Flags.Flag.SEEN)).isFalse();
    }

    private Flags flagsOfLast() throws Exception {
        try (Store s = client()) {
            Folder f = s.getFolder("INBOX");
            f.open(Folder.READ_ONLY);
            Flags flags = f.getMessage(f.getMessageCount()).getFlags();
            f.close(false);
            return flags;
        }
    }

    @Test
    void foreignOrTamperedHandlesAreInvalidHandleWithZeroIo() throws Exception {
        String h = deliver(mixed(attachment("application/pdf", "a.pdf", random(100))));
        long created = pool.createdCount();
        trace.list.clear();
        String bAcct = InMemoryAccountRepository.handle(B, ACCT);

        Map<String, Object> cross = error(call(B, bAcct, h, "2"));

        assertThat(cross).containsEntry("code", "INVALID_HANDLE");
        assertThat(pool.createdCount()).isEqualTo(created);
        assertThat(traceLines()).isEmpty();
    }

    @Test
    void aReadOnlyAccountMayDownload() throws Exception {
        String h = deliver(mixed(attachment("application/pdf", "a.pdf", random(100))));
        assertThat(ok(call(h, "2"))).containsEntry("delivery", "embedded");
    }

    @Test
    void theDefinitionIsReadOnlyAndIdempotent() {
        var def = DownloadAttachmentTool.EMBEDDED_DEFINITION;

        assertThat(def.name()).isEqualTo("download_attachment");
        assertThat(def.hints().readOnly()).isTrue();
        assertThat(def.hints().destructive()).isFalse();
        assertThat(def.hints().idempotent()).isTrue();
        assertThat(def.hints().openWorld()).isTrue();
        assertThat(DescriptionRules.violations(def)).isEmpty();
        assertThat(def.description()).contains(DescriptionRules.READ_ONLY_SENTENCE).contains("untrusted")
                .doesNotContain(DescriptionRules.WRITE_SENTENCE);
        assertThat(def.inputSchema()).isEqualTo(DownloadAttachmentTool.DEFINITION.inputSchema());
        assertThat(def.outputSchema()).isEqualTo(DownloadAttachmentTool.DEFINITION.outputSchema());
    }

    @Test
    void noContentIsLoggedAtDebugEither() throws Exception {
        Logger root = (Logger) LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME);
        Level before = root.getLevel();
        ListAppender<ILoggingEvent> all = new ListAppender<>();
        all.start();
        root.setLevel(Level.DEBUG);
        root.addAppender(all);
        byte[] data = random(100);
        String b64 = java.util.Base64.getEncoder().encodeToString(data);
        String digest = sha(data);
        try {
            String h = deliver(mixed(attachment("application/x-canary-type", "SECRET-NAME.bin", data)));
            ok(call(h, "2"));
            error(call(h, "9"));
        } finally {
            root.detachAppender(all);
            root.setLevel(before);
        }
        assertThat(all.list.stream().filter(e -> e.getLoggerName().startsWith("de.alexanderlindhorst.imapmcp"))
                .map(ILoggingEvent::getFormattedMessage)).isNotEmpty()
                .noneMatch(m -> m.contains("SECRET-NAME") || m.contains("x-canary-type") || m.contains(digest)
                        || m.contains(b64.substring(0, 24)) || m.contains("imap-mcp://attachment/"));
    }

    @Test
    void oneLimiterIsSharedByEveryToolInstanceOfTheReplica() throws Exception {
        limiter = new InlineLimiter(1);
        build(null);
        DownloadAttachmentTool first = tool;
        build(null); // a second tool instance, same limiter
        String h = deliver(mixed(attachment("application/pdf", "a.pdf", random(100))));
        assertThat(limiter.tryAcquire()).isTrue(); // the replica-wide permit is taken elsewhere
        try {
            assertThat(error(call(h, "2"))).containsEntry("code", "SERVER_BUSY");
            assertThat(error(first.handle(new ToolCallContext(new AccountContext(A), "rid"),
                    Map.of("account", acctHandle, "message", h, "part", "2")))).containsEntry("code", "SERVER_BUSY");
        } finally {
            limiter.release();
        }
        assertThat(ok(call(h, "2"))).containsEntry("delivery", "embedded");
    }

    @Test
    void theInlineCapIsOneByteToTenMebibytes() {
        org.junit.jupiter.api.Assertions.assertDoesNotThrow(() -> new DownloadAttachmentTool.InlineSettings(1));
        org.junit.jupiter.api.Assertions.assertDoesNotThrow(
                () -> new DownloadAttachmentTool.InlineSettings(10L * 1024 * 1024));
        for (long bad : new long[] {0, -1, 10L * 1024 * 1024 + 1, Long.MAX_VALUE}) {
            org.assertj.core.api.Assertions.assertThatThrownBy(() -> new DownloadAttachmentTool.InlineSettings(bad))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    /** A deadline between the size check and the fetch is a plain TIMEOUT (nothing was written, no mutation mark). */
    @Test
    void aDeadlineAtTheSizeCheckIsAPlainTimeoutAndTheCallFreesItsPermit() throws Exception {
        limiter = new InlineLimiter(1);
        CountDownLatch inside = new CountDownLatch(1);
        build((step, ctx) -> {
            if (step == DownloadAttachmentTool.Step.AFTER_SIZE_CHECK) {
                inside.countDown();
                try {
                    Thread.sleep(30_000);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
        });
        String h = deliver(mixed(attachment("application/pdf", "a.pdf", random(100))));
        ToolExecutor executor = new ToolExecutor(Duration.ofMillis(800), new LogIds(KEYS));
        ToolCallContext ctx = new ToolCallContext(new AccountContext(A), "rid");

        ToolOutcome o = executor.wrap(DownloadAttachmentTool.EMBEDDED_DEFINITION, tool)
                .handle(ctx, Map.of("account", acctHandle, "message", h, "part", "2"));

        assertThat(inside.await(5, TimeUnit.SECONDS)).isTrue();
        assertThat(error(o)).containsEntry("code", "TIMEOUT");
        assertThat(ctx.mutationStarted()).isFalse();
        long end = System.nanoTime() + Duration.ofSeconds(10).toNanos();
        boolean free = false;
        while (!free && System.nanoTime() < end) {
            free = limiter.tryAcquire();
            if (free) {
                limiter.release();
            } else {
                Thread.sleep(25);
            }
        }
        assertThat(free).as("the permit is released when the abandoned worker unwinds").isTrue();
    }

    @Test
    void aCancelAtTheSizeCheckIsAPlainTimeout() throws Exception {
        limiter = new InlineLimiter(1);
        build((step, ctx) -> {
            if (step == DownloadAttachmentTool.Step.AFTER_SIZE_CHECK) {
                ctx.cancel();
            }
        });
        String h = deliver(mixed(attachment("application/pdf", "a.pdf", random(100))));
        ToolExecutor executor = new ToolExecutor(Duration.ofSeconds(20), new LogIds(KEYS));
        ToolCallContext ctx = new ToolCallContext(new AccountContext(A), "rid");

        ToolOutcome o = executor.wrap(DownloadAttachmentTool.EMBEDDED_DEFINITION, tool)
                .handle(ctx, Map.of("account", acctHandle, "message", h, "part", "2"));

        assertThat(error(o)).containsEntry("code", "TIMEOUT");
        assertThat(ctx.mutationStarted()).isFalse();
    }
}
