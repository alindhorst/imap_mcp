package de.alexanderlindhorst.imapmcp.core.tools;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.icegreen.greenmail.junit5.GreenMailExtension;
import com.icegreen.greenmail.user.GreenMailUser;
import com.icegreen.greenmail.util.ServerSetupTest;
import de.alexanderlindhorst.imapmcp.core.account.InMemoryAccountRepository;
import de.alexanderlindhorst.imapmcp.core.account.MailAccount;
import de.alexanderlindhorst.imapmcp.core.account.MailAccount.Flavor;
import de.alexanderlindhorst.imapmcp.core.account.MailAccount.Security;
import de.alexanderlindhorst.imapmcp.core.account.Secret;
import de.alexanderlindhorst.imapmcp.core.account.SecretRef;
import de.alexanderlindhorst.imapmcp.core.content.UntrustedText;
import de.alexanderlindhorst.imapmcp.core.crypto.KeyRing;
import de.alexanderlindhorst.imapmcp.core.crypto.LogIds;
import de.alexanderlindhorst.imapmcp.core.download.DownloadDirectory;
import de.alexanderlindhorst.imapmcp.core.handle.MessageHandles;
import de.alexanderlindhorst.imapmcp.core.handle.MessageRef;
import de.alexanderlindhorst.imapmcp.core.imap.CapabilityCache;
import de.alexanderlindhorst.imapmcp.core.imap.ImapSessionFactory;
import de.alexanderlindhorst.imapmcp.core.imap.ImapTimeouts;
import de.alexanderlindhorst.imapmcp.core.imap.pool.PoolSettings;
import de.alexanderlindhorst.imapmcp.core.imap.pool.StoreLeases;
import de.alexanderlindhorst.imapmcp.core.imap.pool.StorePool;
import de.alexanderlindhorst.imapmcp.core.metrics.MetricsRecorder;
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
import jakarta.mail.Session;
import jakarta.mail.Store;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeBodyPart;
import jakarta.mail.internet.MimeMessage;
import jakarta.mail.internet.MimeMultipart;
import jakarta.mail.util.ByteArrayDataSource;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Random;
import java.util.stream.Stream;
import org.eclipse.angus.mail.imap.IMAPFolder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.LoggerFactory;

/** SPEC 4 download_attachment: GreenMail through a relaying proxy that can script faults. */
class DownloadAttachmentToolTest {

    static final TenantId A = new TenantId("tenant-a");
    static final TenantId B = new TenantId("tenant-b");
    static final AccountId ACCT = new AccountId("acct");
    static final String MARKER = "FILENAME-MARKER-9f3";
    static final KeyRing KEYS = new KeyRing(1, Map.of(1, new byte[32]));
    static final Session MAIL = Session.getInstance(new java.util.Properties());

    @RegisterExtension
    static final GreenMailExtension GREEN_MAIL = new GreenMailExtension(ServerSetupTest.IMAPS.dynamicPort());

    @TempDir
    Path tmp;

    final MessageHandles handles = new MessageHandles(KEYS);
    final ListAppender<ILoggingEvent> trace = new ListAppender<>();
    Logger traceLogger;
    ImapSessionFactory sessions;
    StorePool pool;
    FaultingImapProxy proxy;
    DownloadAttachmentTool tool;
    DownloadDirectory directory;
    Path dir;
    GreenMailUser user;
    String acctHandle;
    boolean readOnly;
    long maxBytes = 10_000_000;

    MailAccount account(AccountId id) {
        return new MailAccount(id, id.value(), null, "127.0.0.1", proxy.port(), Security.IMPLICIT_TLS, Flavor.GENERIC,
                "user", new SecretRef("env:x"), readOnly);
    }

    @BeforeEach
    void setUp() throws Exception {
        user = GREEN_MAIL.setUser("user@localhost", "user", "secret");
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
        dir = Files.createDirectory(tmp.resolve("downloads"));
        Files.setPosixFilePermissions(dir, PosixFilePermissions.fromString("rwx------"));
        build(null);
    }

    /** (Re)builds pool, directory and tool; {@code hook} may be null. */
    void build(DownloadAttachmentTool.StepHook hook) throws IOException {
        if (pool != null) {
            pool.close();
        }
        MailAccount acct = account(ACCT);
        InMemoryAccountRepository repo = new InMemoryAccountRepository(Map.of(A, List.of(acct), B, List.of(acct)));
        pool = new StorePool(sessions, (t, a) -> Optional.of(new Secret("secret".toCharArray())),
                new CapabilityCache(), k -> acct, PoolSettings.DEFAULTS);
        directory = DownloadDirectory.of(dir.toRealPath(), true, maxBytes, null);
        StoreLeases leases = new StoreLeases(pool, Duration.ofSeconds(10));
        AccountArgument accounts = new AccountArgument(repo);
        MessageLocator locator = new MessageLocator(handles);
        tool = hook == null ? new DownloadAttachmentTool(accounts, locator, leases, directory)
                : new DownloadAttachmentTool(accounts, locator, leases, directory, hook);
        acctHandle = InMemoryAccountRepository.handle(A, ACCT);
    }

    @AfterEach
    void tearDown() throws Exception {
        traceLogger.detachAppender(trace);
        pool.close();
        sessions.close();
        proxy.close();
    }

    // ---- fixtures ----

    static MimeBodyPart attachment(String type, String name, byte[] data) throws Exception {
        MimeBodyPart p = new MimeBodyPart();
        p.setDataHandler(new jakarta.activation.DataHandler(new ByteArrayDataSource(data, type)));
        p.setDisposition("attachment");
        if (name != null) {
            p.setFileName(name);
        }
        return p;
    }

    static MimeBodyPart textAttachment(String name, String text, String charset) throws Exception {
        MimeBodyPart p = new MimeBodyPart();
        p.setText(text, charset);
        p.setDisposition("attachment");
        p.setFileName(name);
        return p;
    }

    static MimeBodyPart bodyText() throws Exception {
        MimeBodyPart p = new MimeBodyPart();
        p.setText("see attached");
        return p;
    }

    static MimeMessage message(MimeMultipart mp) throws Exception {
        MimeMessage m = new MimeMessage(MAIL);
        m.setFrom(new InternetAddress("a@localhost"));
        m.setRecipients(Message.RecipientType.TO, "user@localhost");
        m.setSubject("fixture");
        m.setContent(mp);
        m.saveChanges();
        return m;
    }

    static MimeMessage mixed(MimeBodyPart... parts) throws Exception {
        MimeMultipart mp = new MimeMultipart("mixed");
        mp.addBodyPart(bodyText());
        for (MimeBodyPart p : parts) {
            mp.addBodyPart(p);
        }
        return message(mp);
    }

    static byte[] random(int n) {
        byte[] b = new byte[n];
        new Random(7).nextBytes(b);
        return b;
    }

    Store client() throws Exception {
        java.util.Properties props = new java.util.Properties();
        props.put("mail.imaps.ssl.trust", "*");
        props.put("mail.imaps.ssl.checkserveridentity", "false");
        Store s = Session.getInstance(props).getStore("imaps");
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

    @SuppressWarnings("unchecked")
    static Map<String, Object> untrusted(Map<String, Object> out) {
        return (Map<String, Object>) out.get("untrusted");
    }

    List<String> traceLines() {
        return new ArrayList<>(trace.list.stream().map(ILoggingEvent::getFormattedMessage).toList());
    }

    List<String> filesInDir() throws IOException {
        try (Stream<Path> s = Files.list(dir)) {
            return s.map(p -> p.getFileName().toString()).sorted().toList();
        }
    }

    /** An abandoned worker may still be unwinding after the executor answered: wait for its cleanup. */
    void awaitOnlyFinalNames(String... expected) throws Exception {
        long end = System.nanoTime() + Duration.ofSeconds(10).toNanos();
        while (System.nanoTime() < end && !filesInDir().equals(List.of(expected))) {
            Thread.sleep(25);
        }
        assertThat(filesInDir()).containsExactly(expected);
    }

    static String sha(byte[] b) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(b));
    }

    // ---- AC1: byte-exact content ----

    @Test
    void base64AttachmentIsSavedByteExact() throws Exception {
        byte[] data = random(5000);
        String h = deliver(mixed(attachment("application/pdf", MARKER + ".pdf", data)));

        Map<String, Object> out = ok(call(h, "2"));

        assertThat(out).containsEntry("account", acctHandle).containsEntry("handle", h).containsEntry("part", "2")
                .containsEntry("delivery", "file").containsEntry("directory", directory.reportedDirectory())
                .containsEntry("bytes", 5000L).containsEntry("sha256", sha(data)).containsEntry("renamed", false)
                .containsEntry("content_notice", UntrustedText.CONTENT_NOTICE);
        assertThat(untrusted(out)).containsEntry("filename", MARKER + ".pdf")
                .containsEntry("content_type", "application/pdf").containsEntry("saved_as", MARKER + ".pdf")
                .containsEntry("path", directory.reportedDirectory() + "/" + MARKER + ".pdf");
        Path file = dir.resolve(MARKER + ".pdf");
        assertThat(Files.readAllBytes(file)).isEqualTo(data);
        assertThat(Files.getPosixFilePermissions(file)).isEqualTo(PosixFilePermissions.fromString("rw-------"));
        assertThat(filesInDir()).containsExactly(MARKER + ".pdf");
    }

    @Test
    void quotedPrintableAttachmentIsSavedByteExact() throws Exception {
        String h = deliver(mixed(textAttachment("note.txt", "caf\u00e9 = ok", "ISO-8859-1")));

        Map<String, Object> out = ok(call(h, "2"));

        byte[] expected = "caf\u00e9 = ok".getBytes(StandardCharsets.ISO_8859_1);
        assertThat(Files.readAllBytes(dir.resolve("note.txt"))).isEqualTo(expected);
        assertThat(out).containsEntry("sha256", sha(expected));
    }

    @Test
    void sevenBitAttachmentIsSavedAsSent() throws Exception {
        String h = deliver(mixed(textAttachment("plain.txt", "hello plain", "US-ASCII")));

        ok(call(h, "2"));

        assertThat(Files.readString(dir.resolve("plain.txt"))).isEqualTo("hello plain");
    }

    @Test
    void anyLeafCanBeDownloadedAlsoTheBodyText() throws Exception {
        String h = deliver(mixed(attachment("application/pdf", "a.pdf", random(10))));

        Map<String, Object> out = ok(call(h, "1"));

        assertThat(untrusted(out)).containsEntry("filename", null).containsEntry("content_type", "text/plain")
                .containsEntry("saved_as", "attachment-1");
        assertThat(out).containsEntry("renamed", true);
        assertThat(Files.readString(dir.resolve("attachment-1"))).isEqualTo("see attached");
    }

    @Test
    void messageRfc822IsSavedAsTheEncapsulatedMessage() throws Exception {
        MimeMessage inner = new MimeMessage(MAIL);
        inner.setSubject("inner subject");
        inner.setText("inner body");
        inner.saveChanges();
        MimeBodyPart fwd = new MimeBodyPart();
        fwd.setContent(inner, "message/rfc822");
        fwd.setDisposition("attachment");
        String h = deliver(mixed(fwd));

        Map<String, Object> out = ok(call(h, "2"));

        assertThat(untrusted(out)).containsEntry("saved_as", "attachment-2.eml")
                .containsEntry("content_type", "message/rfc822");
        // GreenMail serves the text of the encapsulated message here; the Dovecot IT checks the headers
        String saved = Files.readString(dir.resolve("attachment-2.eml"));
        assertThat(saved).contains("inner body");
    }

    // ---- part resolution errors ----

    @Test
    void partErrors() throws Exception {
        MimeMessage inner = new MimeMessage(MAIL);
        inner.setSubject("inner");
        inner.setText("inner body");
        inner.saveChanges();
        MimeBodyPart fwd = new MimeBodyPart();
        fwd.setContent(inner, "message/rfc822");
        fwd.setDisposition("attachment");
        MimeMultipart alt = new MimeMultipart("alternative");
        MimeBodyPart a1 = new MimeBodyPart();
        a1.setText("one");
        alt.addBodyPart(a1);
        MimeBodyPart altPart = new MimeBodyPart();
        altPart.setContent(alt);
        MimeMultipart top = new MimeMultipart("mixed");
        top.addBodyPart(altPart);
        top.addBodyPart(fwd);
        String h = deliver(message(top));

        Map<String, Object> missing = error(call(h, "9"));
        assertThat(missing).containsEntry("code", "INVALID_ARGUMENT");
        assertThat(missing.get("details")).isEqualTo(Map.of("field", "part", "reason", "part_not_found"));
        Map<String, Object> below = error(call(h, "2.1"));
        assertThat(below.get("details")).isEqualTo(Map.of("field", "part", "reason", "part_not_found"));
        assertThat((String) below.get("hint")).contains("message/rfc822 part itself");
        Map<String, Object> notLeaf = error(call(h, "1"));
        assertThat(notLeaf).containsEntry("code", "INVALID_ARGUMENT");
        assertThat(notLeaf.get("details")).isEqualTo(Map.of("field", "part", "reason", "not_a_leaf"));
        assertThat(filesInDir()).isEmpty();
    }

    @Test
    void anUnknownTransferEncodingIsUnsupportedBeforeAnyBodyFetch() throws Exception {
        String raw = "From: a@localhost\r\nSubject: x\r\nMIME-Version: 1.0\r\nContent-Type: application/octet-stream\r\n"
                + "Content-Transfer-Encoding: x-uuencode\r\n\r\nbegin 644 a\r\nend\r\n";
        try (Store s = client()) {
            IMAPFolder f = (IMAPFolder) s.getFolder("INBOX");
            f.open(Folder.READ_WRITE);
            f.doCommand(p -> {
                p.append("INBOX", null, null, new org.eclipse.angus.mail.iap.Literal() {
                    public int size() {
                        return raw.length();
                    }

                    public void writeTo(java.io.OutputStream os) throws IOException {
                        os.write(raw.getBytes(StandardCharsets.ISO_8859_1));
                    }
                });
                return null;
            });
            Message last = f.getMessage(f.getMessageCount());
            String h = handles.encode(A, ACCT, new MessageRef("INBOX", f.getUIDValidity(), f.getUID(last)));
            f.close(false);
            trace.list.clear();

            Map<String, Object> e = error(call(h, "1"));

            assertThat(e).containsEntry("code", "UNSUPPORTED");
            assertThat(e.get("details")).isEqualTo(Map.of("reason", "transfer_encoding"));
            assertThat(traceLines()).noneMatch(l -> l.contains("BODY.PEEK["));
            assertThat(filesInDir()).isEmpty();
        }
    }

    // ---- size ----

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
        assertThat((Long) d.get("size")).isBetween(4900L, 5100L);
        assertThat(traceLines()).noneMatch(l -> l.contains("BODY.PEEK["));
        assertThat(filesInDir()).isEmpty();
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
        assertThat(filesInDir()).isEmpty();
    }

    // ---- PEEK, tenant isolation, directory ----

    @Test
    void fetchesOnlyPeekPartialRangesViaExamineAndLeavesAllFlagsUnchanged() throws Exception {
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
    void foreignOrTamperedHandlesAreInvalidHandleWithZeroIoAndNoFile() throws Exception {
        String h = deliver(mixed(attachment("application/pdf", "a.pdf", random(100))));
        long created = pool.createdCount();
        trace.list.clear();
        String bAcct = InMemoryAccountRepository.handle(B, ACCT);

        Map<String, Object> cross = error(call(B, bAcct, h, "2"));
        Map<String, Object> tampered = error(call(B, bAcct, h.substring(0, h.length() - 2) + "AA", "2"));

        assertThat(cross).containsEntry("code", "INVALID_HANDLE").isEqualTo(tampered);
        assertThat(error(call(A, acctHandle, h.substring(0, h.length() - 2) + "AA", "2")))
                .containsEntry("code", "INVALID_HANDLE");
        assertThat(pool.createdCount()).isEqualTo(created);
        assertThat(traceLines()).isEmpty();
        assertThat(filesInDir()).isEmpty();
    }

    @Test
    void unknownAccountWinsOverEverythingElse() throws Exception {
        assertThat(error(call(A, "acc_aaaaaaaaaaaaaaaaaaaaaaaaaa", "m1.garbage", "2")))
                .containsEntry("code", "ACCOUNT_NOT_FOUND");
        assertThat(pool.createdCount()).isZero();
    }

    @Test
    void aRemovedDirectoryIsDirectoryUnavailableBeforeAnyImapIo() throws Exception {
        String h = deliver(mixed(attachment("application/pdf", "a.pdf", random(100))));
        Files.delete(dir);
        trace.list.clear();

        Map<String, Object> e = error(call(h, "2"));

        assertThat(e).containsEntry("code", "LOCAL_STORAGE_ERROR");
        assertThat(e.get("details")).isEqualTo(Map.of("reason", "directory_unavailable"));
        assertThat(pool.createdCount()).isZero();
        assertThat(traceLines()).isEmpty();
    }

    @Test
    void aReadOnlyAccountMayDownload() throws Exception {
        readOnly = true;
        build(null);
        String h = deliver(mixed(attachment("application/pdf", "a.pdf", random(100))));

        assertThat(ok(call(h, "2"))).containsEntry("bytes", 100L);
    }

    // ---- names and clashes ----

    @Test
    void aClashingNameGetsANumberAndTheFirstFileIsUntouched() throws Exception {
        byte[] data = random(100);
        String h = deliver(mixed(attachment("application/pdf", "a.pdf", data)));
        ok(call(h, "2"));

        Map<String, Object> second = ok(call(h, "2"));

        assertThat(untrusted(second)).containsEntry("filename", "a.pdf").containsEntry("saved_as", "a (2).pdf");
        assertThat(second).containsEntry("renamed", true);
        assertThat(Files.readAllBytes(dir.resolve("a.pdf"))).isEqualTo(data);
        assertThat(filesInDir()).containsExactly("a (2).pdf", "a.pdf");
    }

    @Test
    void anUnsafeNameIsSanitizedAndMarkedRenamed() throws Exception {
        String h = deliver(mixed(attachment("application/pdf", "r\u00e9sum\u00e9:1.pdf", random(100))));

        Map<String, Object> out = ok(call(h, "2"));

        assertThat(untrusted(out)).containsEntry("filename", "r\u00e9sum\u00e9:1.pdf")
                .containsEntry("saved_as", "r\u00e9sum\u00e9_1.pdf");
        assertThat(out).containsEntry("renamed", true);
    }

    @Test
    @SuppressWarnings("unchecked")
    void emailDerivedValuesAppearOnlyUnderUntrusted() throws Exception {
        String h = deliver(mixed(attachment("application/pdf", MARKER + ".pdf", random(100))));

        Map<String, Object> out = new java.util.HashMap<>(ok(call(h, "2")));
        Map<String, Object> untrusted = (Map<String, Object>) out.remove("untrusted");

        assertThat(out.toString()).doesNotContain(MARKER);
        assertThat(untrusted.toString()).contains(MARKER);
        assertThat(untrusted).containsOnlyKeys("filename", "content_type", "saved_as", "path");
    }

    // ---- fetch failures: no file, errors by origin ----

    @Test
    void aNoAnswerToTheFirstBlockIsMessageNotFound() throws Exception {
        String h = deliver(mixed(attachment("application/pdf", "a.pdf", random(100))));
        proxy.rejectWithNo("BODY.PEEK", "gone");

        assertThat(error(call(h, "2"))).containsEntry("code", "MESSAGE_NOT_FOUND");
        assertThat(filesInDir()).isEmpty();
    }

    @Test
    void aNilOrNoBlockLaterIsUpstreamError() throws Exception {
        String h = deliver(mixed(attachment("application/pdf", "a.pdf", random(1_300_000))));
        proxy.rejectWithNoAfter("BODY.PEEK", "gone", 1);

        assertThat(error(call(h, "2"))).containsEntry("code", "UPSTREAM_ERROR");
        assertThat(filesInDir()).isEmpty();
    }

    @Test
    void aConnectionResetMidPartDiscardsTheStoreAndLeavesNoFile() throws Exception {
        String h = deliver(mixed(attachment("application/pdf", "a.pdf", random(1_300_000))));
        proxy.dropConnectionAfter("BODY.PEEK", 1);
        long destroyedBefore = pool.destroyedCount();

        assertThat(error(call(h, "2"))).containsEntry("code", "UPSTREAM_UNAVAILABLE");

        assertThat(pool.destroyedCount()).isGreaterThan(destroyedBefore);
        assertThat(filesInDir()).isEmpty();
    }

    @Test
    void aTruncatedBase64QuantumIsDecodeFailedAndLeavesNoFile() throws Exception {
        String h = deliver(mixed(attachment("application/pdf", "a.pdf", random(100))));
        proxy.serveBody("BODY.PEEK", "AAAABB".getBytes(StandardCharsets.ISO_8859_1));

        Map<String, Object> e = error(call(h, "2"));

        assertThat(e).containsEntry("code", "UNSUPPORTED");
        assertThat(e.get("details")).isEqualTo(Map.of("reason", "decode_failed"));
        assertThat(filesInDir()).isEmpty();
    }

    // ---- 4: deadline race with tryBeginMutation (SPEC 5.5) ----

    private ToolExecutor executor() {
        return new ToolExecutor(Duration.ofSeconds(30), new LogIds(KEYS), MetricsRecorder.NOOP);
    }

    private ToolOutcome wrapped(String h, DownloadAttachmentTool.StepHook hook) throws Exception {
        build(hook);
        ToolCallContext ctx = new ToolCallContext(new AccountContext(A), "rid");
        return executor().wrap(DownloadAttachmentTool.DEFINITION, tool).handle(ctx,
                Map.of("account", acctHandle, "message", h, "part", "2"));
    }

    @Test
    void aCancelBeforePublicationIsAPlainTimeoutAndPublishesNothing() throws Exception {
        String h = deliver(mixed(attachment("application/pdf", "a.pdf", random(100))));

        ToolOutcome o = wrapped(h, (step, ctx) -> {
            if (step == DownloadAttachmentTool.Step.BEFORE_PUBLISH) {
                ctx.cancel();
            }
        });

        Map<String, Object> e = error(o);
        assertThat(e).containsEntry("code", "TIMEOUT");
        assertThat(e).doesNotContainKey("details");
        awaitOnlyFinalNames();
    }

    @Test
    void aCancelBetweenSizeCheckAndFetchIsAPlainTimeoutAndLeavesNoFile() throws Exception {
        String h = deliver(mixed(attachment("application/pdf", "a.pdf", random(100))));

        ToolOutcome o = wrapped(h, (step, ctx) -> {
            if (step == DownloadAttachmentTool.Step.AFTER_SIZE_CHECK) {
                ctx.cancel();
            }
        });

        assertThat(error(o)).containsEntry("code", "TIMEOUT");
        awaitOnlyFinalNames();
    }

    @Test
    void aCancelRightAfterTheMutationMarkIsOutcomeUnknown() throws Exception {
        String h = deliver(mixed(attachment("application/pdf", "a.pdf", random(100))));

        ToolOutcome o = wrapped(h, (step, ctx) -> {
            if (step == DownloadAttachmentTool.Step.AFTER_BEGIN_MUTATION) {
                ctx.cancel();
            }
        });

        assertThat(error(o)).containsEntry("code", "OUTCOME_UNKNOWN");
        awaitOnlyFinalNames();
    }

    @Test
    void withoutACancelTheExecutorWrappedCallSucceeds() throws Exception {
        String h = deliver(mixed(attachment("application/pdf", "a.pdf", random(100))));

        assertThat(ok(wrapped(h, (step, ctx) -> { }))).containsEntry("bytes", 100L);
    }

    // ---- definition and logging ----

    @Test
    void definitionFollowsTheSpec() {
        var def = DownloadAttachmentTool.DEFINITION;

        assertThat(def.name()).isEqualTo("download_attachment");
        assertThat(def.hints().readOnly()).isFalse();
        assertThat(def.hints().destructive()).isFalse();
        assertThat(def.hints().idempotent()).isFalse();
        assertThat(def.hints().openWorld()).isTrue();
        assertThat(DescriptionRules.violations(def)).isEmpty();
        assertThat(def.description()).contains(DescriptionRules.WRITE_SENTENCE).contains("Never overwrites")
                .contains("untrusted");
        assertThat(def.description()).doesNotContain(DescriptionRules.READ_ONLY_SENTENCE);
    }

    @Test
    void theSchemaPinsThePartPattern() {
        @SuppressWarnings("unchecked")
        Map<String, Object> props = (Map<String, Object>) DownloadAttachmentTool.DEFINITION.inputSchema()
                .get("properties");
        @SuppressWarnings("unchecked")
        Map<String, Object> part = (Map<String, Object>) props.get("part");
        assertThat(part).containsEntry("pattern", "^[1-9][0-9]*(?:\\.[1-9][0-9]*)*$")
                .containsEntry("minLength", 1).containsEntry("maxLength", 128);
        assertThat(DownloadAttachmentTool.DEFINITION.inputSchema().get("required"))
                .isEqualTo(List.of("account", "message", "part"));
    }

    @Test
    void oneInfoLineWithBytesAndRenamedAndNothingElseIsLoggedAtAnyLevel() throws Exception {
        Logger root = (Logger) LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME);
        ListAppender<ILoggingEvent> all = new ListAppender<>();
        all.start();
        Level old = root.getLevel();
        root.setLevel(Level.TRACE);
        root.addAppender(all);
        try {
            String h = deliver(mixed(attachment("application/pdf", "canary-name.pdf", random(100))));
            ok(call(h, "2"));
            ok(call(h, "2"));
            proxy.rejectWithNo("BODY.PEEK", "canary-name");
            error(call(h, "2"));
        } finally {
            root.detachAppender(all);
            root.setLevel(old);
        }
        List<ILoggingEvent> app = all.list.stream().filter(e -> e.getLoggerName().startsWith("de.alexanderlindhorst."))
                .toList();
        assertThat(app).noneMatch(e -> e.getFormattedMessage().contains("canary")
                || e.getFormattedMessage().contains(dir.toString())
                || e.getThrowableProxy() != null && String.valueOf(e.getThrowableProxy().getMessage()).contains("canary"));
        List<String> info = app.stream().filter(e -> e.getLevel() == Level.INFO)
                .map(ILoggingEvent::getFormattedMessage).filter(m -> m.contains("download_attachment")).toList();
        assertThat(info).hasSize(3);
        assertThat(info.get(0)).contains("bytes=100").contains("renamed=false");
        assertThat(info.get(1)).contains("bytes=100").contains("renamed=true");
        assertThat(info.get(2)).contains("error_code=MESSAGE_NOT_FOUND");
    }
}
