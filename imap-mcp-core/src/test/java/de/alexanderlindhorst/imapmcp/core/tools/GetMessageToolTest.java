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
import de.alexanderlindhorst.imapmcp.core.handle.MessageHandles;
import de.alexanderlindhorst.imapmcp.core.handle.MessageRef;
import de.alexanderlindhorst.imapmcp.core.imap.CapabilityCache;
import de.alexanderlindhorst.imapmcp.core.imap.ImapSessionFactory;
import de.alexanderlindhorst.imapmcp.core.imap.ImapTimeouts;
import de.alexanderlindhorst.imapmcp.core.imap.pool.PoolSettings;
import de.alexanderlindhorst.imapmcp.core.mime.BodyWindowReader;
import de.alexanderlindhorst.imapmcp.core.tool.ValidatingToolHandler;
import de.alexanderlindhorst.imapmcp.core.imap.pool.StoreLeases;
import de.alexanderlindhorst.imapmcp.core.imap.pool.StorePool;
import de.alexanderlindhorst.imapmcp.core.tenancy.AccountContext;
import de.alexanderlindhorst.imapmcp.core.tenancy.AccountId;
import de.alexanderlindhorst.imapmcp.core.tenancy.TenantId;
import de.alexanderlindhorst.imapmcp.core.tool.ToolCallContext;
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
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.eclipse.angus.mail.imap.IMAPFolder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.slf4j.LoggerFactory;

class GetMessageToolTest {

    static final TenantId A = new TenantId("tenant-a");
    static final TenantId B = new TenantId("tenant-b");
    static final AccountId ACCT = new AccountId("acct");
    static final AccountId OTHER = new AccountId("other");
    static final String MARKER = "MARKER-9f3";
    static final KeyRing KEYS = new KeyRing(1, Map.of(1, new byte[32]));

    @RegisterExtension
    static final GreenMailExtension GREEN_MAIL = new GreenMailExtension(ServerSetupTest.IMAPS.dynamicPort());

    final MessageHandles handles = new MessageHandles(KEYS);
    final ListAppender<ILoggingEvent> trace = new ListAppender<>();
    Logger traceLogger;
    ImapSessionFactory sessions;
    StorePool pool;
    InMemoryAccountRepository repo;
    GetMessageTool tool;
    ListAttachmentsTool attachments;
    MessageLocator locator;
    StoreLeases leases;
    GreenMailUser user;
    String acctHandle;
    String otherHandle;

    MailAccount account(AccountId id) {
        return new MailAccount(id, id.value(), null, "127.0.0.1", GREEN_MAIL.getImaps().getPort(),
                Security.IMPLICIT_TLS, Flavor.GENERIC, "user", new SecretRef("env:x"));
    }

    @BeforeEach
    void setUp() {
        user = GREEN_MAIL.setUser("user@localhost", "user", "secret");
        sessions = new ImapSessionFactory(ImapTimeouts.DEFAULTS, p -> {
            p.put("mail.imaps.ssl.trust", "*");
            p.put("mail.imaps.ssl.checkserveridentity", "false");
            return p;
        }, true);
        Map<AccountId, MailAccount> byId = Map.of(ACCT, account(ACCT), OTHER, account(OTHER));
        repo = new InMemoryAccountRepository(Map.of(A, List.of(byId.get(ACCT), byId.get(OTHER)),
                B, List.of(byId.get(ACCT))));
        pool = new StorePool(sessions, (t, a) -> Optional.of(new Secret("secret".toCharArray())),
                new CapabilityCache(), k -> byId.get(k.accountId()), PoolSettings.DEFAULTS);
        locator = new MessageLocator(handles);
        leases = new StoreLeases(pool, Duration.ofSeconds(10));
        tool = new GetMessageTool(new AccountArgument(repo), locator, leases, new BodyWindowReader(), 100000);
        attachments = new ListAttachmentsTool(new AccountArgument(repo), locator, leases);
        acctHandle = InMemoryAccountRepository.handle(A, ACCT);
        otherHandle = InMemoryAccountRepository.handle(A, OTHER);
        traceLogger = (Logger) LoggerFactory.getLogger("imapmcp.imap.trace");
        traceLogger.setLevel(Level.DEBUG);
        traceLogger.addAppender(trace);
        trace.start();
    }

    @AfterEach
    void tearDown() {
        traceLogger.detachAppender(trace);
        pool.close();
        sessions.close();
    }

    // fixtures

    static final Session MAIL = Session.getInstance(new java.util.Properties());

    static MimeMessage plain(String subject, String text) throws Exception {
        MimeMessage m = new MimeMessage(MAIL);
        m.setFrom(new InternetAddress("alice@example.org", "Alice A"));
        m.setRecipients(Message.RecipientType.TO, "user@localhost");
        m.setSubject(subject, "UTF-8");
        m.setText(text, "UTF-8");
        m.saveChanges();
        return m;
    }

    static MimeMessage html(String html) throws Exception {
        MimeMessage m = new MimeMessage(MAIL);
        m.setFrom(new InternetAddress("alice@example.org"));
        m.setRecipients(Message.RecipientType.TO, "user@localhost");
        m.setSubject("html");
        m.setText(html, "UTF-8", "html");
        m.saveChanges();
        return m;
    }

    static MimeMessage withAttachment() throws Exception {
        MimeMultipart mixed = new MimeMultipart("mixed");
        MimeBodyPart text = new MimeBodyPart();
        text.setText("see attached", "UTF-8");
        mixed.addBodyPart(text);
        MimeBodyPart p = new MimeBodyPart();
        p.setDataHandler(new jakarta.activation.DataHandler(new ByteArrayDataSource(new byte[500], "application/pdf")));
        p.setDisposition("attachment");
        p.setFileName(MARKER + ".pdf");
        mixed.addBodyPart(p);
        MimeMessage m = new MimeMessage(MAIL);
        m.setFrom(new InternetAddress("alice@example.org"));
        m.setRecipients(Message.RecipientType.TO, "user@localhost");
        m.setSubject("att");
        m.setContent(mixed);
        m.saveChanges();
        return m;
    }

    Store client() throws Exception {
        java.util.Properties props = new java.util.Properties();
        props.put("mail.imaps.ssl.trust", "*");
        props.put("mail.imaps.ssl.checkserveridentity", "false");
        Store s = Session.getInstance(props).getStore("imaps");
        s.connect("127.0.0.1", GREEN_MAIL.getImaps().getPort(), "user", "secret");
        return s;
    }

    String deliver(String folder, MimeMessage msg, TenantId tenant, AccountId acct) throws Exception {
        try (Store s = client()) {
            IMAPFolder f = (IMAPFolder) s.getFolder(folder);
            if (!f.exists()) {
                f.create(Folder.HOLDS_MESSAGES);
            }
            f.open(Folder.READ_WRITE);
            f.appendMessages(new Message[] {msg});
            Message last = f.getMessage(f.getMessageCount());
            MessageRef ref = new MessageRef(folder, f.getUIDValidity(), f.getUID(last));
            f.close(false);
            return handles.encode(tenant, acct, ref);
        }
    }

    String deliver(MimeMessage msg) throws Exception {
        return deliver("INBOX", msg, A, ACCT);
    }

    ToolOutcome call(TenantId tenant, String account, String message, Map<String, Object> extra) {
        Map<String, Object> args = new HashMap<>(extra);
        args.put("account", account);
        args.put("message", message);
        return tool.handle(new ToolCallContext(new AccountContext(tenant), "rid"), args);
    }

    ToolOutcome call(String message, Map<String, Object> extra) {
        return call(A, acctHandle, message, extra);
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

    @SuppressWarnings("unchecked")
    static Map<String, Object> body(Map<String, Object> out) {
        return (Map<String, Object>) untrusted(out).get("body");
    }

    List<String> traceLines() {
        return new ArrayList<>(trace.list.stream().map(ILoggingEvent::getFormattedMessage).toList());
    }

    // AC1

    @Test
    @SuppressWarnings("unchecked")
    void plainMessageReturnsEnvelopeBodyAndOuterFields() throws Exception {
        String h = deliver(plain("Hello \u00fcber", "line one\nline two"));
        Map<String, Object> out = ok(call(h, Map.of()));
        assertThat(out).containsEntry("account", acctHandle).containsEntry("handle", h)
                .containsEntry("folder", "INBOX").containsEntry("content_notice", UntrustedText.CONTENT_NOTICE);
        assertThat(out.get("size")).isInstanceOf(Number.class);
        assertThat((String) out.get("received_at")).matches("\\d{4}-\\d{2}-\\d{2}T.*Z");
        assertThat((List<String>) out.get("flags")).doesNotContain("\\Seen");
        Map<String, Object> u = untrusted(out);
        assertThat(u).containsEntry("subject", "Hello \u00fcber").containsEntry("recipients_truncated", false);
        assertThat((Map<String, Object>) u.get("from")).containsEntry("name", "Alice A")
                .containsEntry("address", "alice@example.org");
        assertThat((List<Map<String, Object>>) u.get("to")).extracting(m -> m.get("address"))
                .containsExactly("user@localhost");
        assertThat((List<?>) u.get("cc")).isEmpty();
        // an absent Reply-To is reported by the server as the From address (RFC 3501 envelope rules)
        assertThat((List<Map<String, Object>>) u.get("reply_to")).extracting(m -> m.get("address"))
                .containsExactly("alice@example.org");
        assertThat((String) u.get("message_id")).startsWith("<");
        assertThat(u).containsKey("in_reply_to");
        assertThat(u.get("sent_at")).isNotNull();
        assertThat(u).doesNotContainKeys("headers", "headers_truncated");
        Map<String, Object> b = body(out);
        assertThat(b).containsEntry("text", "line one\nline two").containsEntry("truncated", false)
                .containsEntry("converted_from_html", false).containsEntry("content_type", "text/plain")
                .containsEntry("offset", 0L).containsEntry("returned_chars", 17).containsEntry("total_chars", 17L)
                .containsEntry("source_truncated", false);
        assertThat(b.get("next_offset")).isNull();
        assertThat(b).containsKey("next_offset");
        assertThat(out).containsEntry("attachments_total", 0).containsEntry("attachments_truncated", false);
    }

    /** #137 AC4: user keywords follow the system flags, sorted. */
    @Test
    @SuppressWarnings("unchecked")
    void userKeywordsAreSortedAfterTheSystemFlags() throws Exception {
        MimeMessage m = plain("kw", "body");
        m.setFlags(new Flags("zeta"), true);
        m.setFlags(new Flags("alpha"), true);
        m.setFlag(Flags.Flag.FLAGGED, true);
        String h = deliver(m);
        assertThat((List<String>) ok(call(h, Map.of())).get("flags")).containsExactly("\\Flagged", "alpha", "zeta");
    }

    /** #137 AC4: no more than 50 flags are returned. */
    @Test
    @SuppressWarnings("unchecked")
    void moreThanFiftyKeywordsAreCappedAtFifty() throws Exception {
        MimeMessage m = plain("many", "body");
        for (int i = 0; i < 70; i++) {
            m.setFlags(new Flags(String.format("kw%03d", i)), true);
        }
        String h = deliver(m);
        List<String> flags = (List<String>) ok(call(h, Map.of())).get("flags");
        assertThat(flags).hasSize(50).startsWith("kw000", "kw001").doesNotContain("kw069");
    }

    @Test
    void htmlOnlyMessageIsConvertedToText() throws Exception {
        String h = deliver(html("<p>Hello <b>world</b></p>"));
        Map<String, Object> b = body(ok(call(h, Map.of())));
        assertThat(b).containsEntry("converted_from_html", true).containsEntry("content_type", "text/html");
        assertThat((String) b.get("text")).contains("Hello").contains("world").doesNotContain("<b>");
    }

    @Test
    void decodesEncodedWordsInSubjectAndDisplayNamesAndKeepsOriginalSentOffset() throws Exception {
        MimeMessage m = plain("x", "b");
        m.setHeader("Subject", "=?UTF-8?B?R3LDvMOfZQ==?=");
        m.setHeader("From", "=?UTF-8?Q?J=C3=BCrgen?= <j@example.org>");
        m.setHeader("Date", "Tue, 3 Jun 2025 11:05:30 +0200");
        m.setHeader("Cc", "c1@example.org, c2@example.org");
        m.setHeader("Reply-To", "r@example.org");
        m.setHeader("In-Reply-To", "<parent@example.org>");
        m.setHeader("Message-ID", "<mine@example.org>");
        Map<String, Object> u = untrusted(ok(call(deliver(m), Map.of())));
        assertThat(u).containsEntry("subject", "Gr\u00fc\u00dfe").containsEntry("sent_at", "2025-06-03T11:05:30+02:00")
                .containsEntry("in_reply_to", "<parent@example.org>").containsEntry("message_id", "<mine@example.org>");
        assertThat(((Map<?, ?>) u.get("from")).get("name")).isEqualTo("J\u00fcrgen");
        assertThat((List<?>) u.get("cc")).hasSize(2);
        assertThat((List<?>) u.get("reply_to")).hasSize(1);
    }

    @Test
    void missingOptionalFieldsAreNull() throws Exception {
        MimeMessage m = new MimeMessage(MAIL);
        m.setText("only body");
        m.saveChanges();
        m.removeHeader("Message-ID");
        m.removeHeader("Date");
        Map<String, Object> u = untrusted(ok(call(deliver(m), Map.of())));
        assertThat(u).containsEntry("from", null).containsEntry("subject", null)
                .containsEntry("in_reply_to", null);
    }

    @Test
    @SuppressWarnings("unchecked")
    void recipientsAreCappedAt100() throws Exception {
        MimeMessage m = plain("many", "b");
        StringBuilder to = new StringBuilder();
        for (int i = 0; i < 120; i++) {
            to.append(i == 0 ? "" : ", ").append("u").append(i).append("@example.org");
        }
        m.setHeader("To", to.toString());
        Map<String, Object> u = untrusted(ok(call(deliver(m), Map.of())));
        assertThat((List<?>) u.get("to")).hasSize(100);
        assertThat(u).containsEntry("recipients_truncated", true);
    }

    // AC2

    @Test
    void longBodyIsWindowedAndContinuationReturnsNextWindow() throws Exception {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 5000; i++) {
            sb.append((char) ('a' + i % 26));
        }
        String full = sb.toString();
        String h = deliver(plain("long", full));
        Map<String, Object> first = body(ok(call(h, Map.of("max_chars", 1000))));
        assertThat(first).containsEntry("truncated", true).containsEntry("next_offset", 1000L)
                .containsEntry("returned_chars", 1000);
        assertThat(first.get("text")).isEqualTo(full.substring(0, 1000));
        Map<String, Object> second = body(ok(call(h, Map.of("max_chars", 1000, "offset", 1000))));
        assertThat(second.get("text")).isEqualTo(full.substring(1000, 2000));
        assertThat(second).containsEntry("offset", 1000L).containsEntry("next_offset", 2000L);
    }

    // AC3

    @Test
    @SuppressWarnings("unchecked")
    void attachmentsEqualTheListAttachmentsResultForTheSameHandle() throws Exception {
        String h = deliver(withAttachment());
        Map<String, Object> out = ok(call(h, Map.of()));
        Map<String, Object> listed = ok(attachments.handle(new ToolCallContext(new AccountContext(A), "rid"),
                Map.of("account", acctHandle, "message", h)));
        assertThat(out.get("attachments")).isEqualTo(listed.get("attachments"));
        assertThat(out.get("attachments_total")).isEqualTo(listed.get("total"));
        assertThat(out.get("attachments_truncated")).isEqualTo(listed.get("truncated"));
        assertThat((List<?>) out.get("attachments")).hasSize(1);
        assertThat(body(out)).containsEntry("text", "see attached");
    }

    // AC4

    @Test
    @SuppressWarnings("unchecked")
    void includeHeadersFalseOmitsKeysAndTrueReturnsFieldsInOrder() throws Exception {
        String h = deliver(plain("hdr", "b"));
        assertThat(untrusted(ok(call(h, Map.of("include_headers", false))))).doesNotContainKeys("headers",
                "headers_truncated");
        Map<String, Object> u = untrusted(ok(call(h, Map.of("include_headers", true))));
        List<Map<String, Object>> headers = (List<Map<String, Object>>) u.get("headers");
        assertThat(headers).extracting(m -> m.get("name")).contains("From", "To", "Subject");
        assertThat(headers.get(0)).containsOnlyKeys("name", "value");
        assertThat(u).containsEntry("headers_truncated", false);
    }

    @Test
    @SuppressWarnings("unchecked")
    void hundredFiftyHeadersAreCappedAt100() throws Exception {
        MimeMessage m = plain("many headers", "b");
        for (int i = 0; i < 150; i++) {
            m.addHeader("X-H" + i, "v" + i);
        }
        Map<String, Object> u = untrusted(ok(call(deliver(m), Map.of("include_headers", true))));
        assertThat((List<?>) u.get("headers")).hasSize(100);
        assertThat(u).containsEntry("headers_truncated", true);
    }

    // AC5

    @Test
    void usesExamineAndPeekOnlyAndLeavesAllFlagsUntouched() throws Exception {
        String h = deliver(withAttachment());
        Flags before;
        try (Store s = client()) {
            Folder f = s.getFolder("INBOX");
            f.open(Folder.READ_ONLY);
            before = f.getMessage(f.getMessageCount()).getFlags();
            f.close(false);
        }
        trace.list.clear();
        ok(call(h, Map.of("include_headers", true)));
        List<String> lines = traceLines().stream().filter(l -> !l.startsWith("* ") && !l.startsWith("DEBUG")).toList();
        assertThat(lines).anyMatch(l -> l.contains("EXAMINE"));
        assertThat(lines).anyMatch(l -> l.contains("BODY.PEEK[HEADER]"));
        assertThat(lines).anyMatch(l -> l.contains("BODY.PEEK[1]"));
        assertThat(lines).noneMatch(l -> l.contains("FETCH") && l.contains("BODY[") && !l.contains("BODY.PEEK["));
        assertThat(lines).noneMatch(l -> l.contains("RFC822 ") || l.contains("RFC822.TEXT") || l.contains("RFC822.HEADER")
                || l.contains(" SELECT ") || l.contains(" STORE ") || l.contains("EXPUNGE"));
        try (Store s = client()) {
            Folder f = s.getFolder("INBOX");
            f.open(Folder.READ_ONLY);
            Flags after = f.getMessage(f.getMessageCount()).getFlags();
            assertThat(after.contains(Flags.Flag.SEEN)).isFalse();
            assertThat(after.getSystemFlags()).containsExactlyInAnyOrder(before.getSystemFlags());
            f.close(false);
        }
    }

    @Test
    void withoutHeadersOnlyTheDateFieldIsPeeked() throws Exception {
        String h = deliver(plain("x", "b"));
        trace.list.clear();
        ok(call(h, Map.of()));
        assertThat(traceLines()).noneMatch(l -> l.contains("BODY.PEEK[HEADER]"));
        assertThat(traceLines()).anyMatch(l -> l.contains("BODY.PEEK[HEADER.FIELDS (DATE)]"));
    }

    // AC6

    @Test
    void tamperedHandleIsInvalidHandleWithoutLeaseOrImapCommand() throws Exception {
        String h = deliver(plain("x", "b"));
        long created = pool.createdCount();
        trace.list.clear();
        String tampered = h.substring(0, h.length() - 2) + (h.endsWith("AA") ? "BB" : "AA");
        assertThat(error(call(tampered, Map.of()))).containsEntry("code", "INVALID_HANDLE");
        assertThat(pool.createdCount()).isEqualTo(created);
        assertThat(traceLines()).isEmpty();
    }

    @Test
    void otherTenantsContextGetsInvalidHandleIdenticalToTampered() throws Exception {
        String h = deliver(plain("x", "b"));
        String bAcct = InMemoryAccountRepository.handle(B, ACCT);
        Map<String, Object> cross = error(call(B, bAcct, h, Map.of()));
        Map<String, Object> tampered = error(call(B, bAcct, h.substring(0, h.length() - 2) + "AA", Map.of()));
        assertThat(cross).containsEntry("code", "INVALID_HANDLE").isEqualTo(tampered);
    }

    @Test
    void foreignAccountHandleIsAccountNotFound() throws Exception {
        String h = deliver(plain("x", "b"));
        assertThat(error(call(A, "acc_aaaaaaaaaaaaaaaaaaaaaaaaaa", h, Map.of()))).containsEntry("code",
                "ACCOUNT_NOT_FOUND");
    }

    @Test
    void handleOfAnotherAccountOfTheSameTenantIsInvalid() throws Exception {
        String h = deliver(plain("x", "b"));
        assertThat(error(call(A, otherHandle, h, Map.of()))).containsEntry("code", "INVALID_HANDLE");
    }

    @Test
    void expungedMessageIsMessageNotFound() throws Exception {
        String h = deliver(plain("x", "b"));
        try (Store s = client()) {
            Folder f = s.getFolder("INBOX");
            f.open(Folder.READ_WRITE);
            f.getMessage(f.getMessageCount()).setFlag(Flags.Flag.DELETED, true);
            f.close(true);
        }
        assertThat(error(call(h, Map.of()))).containsEntry("code", "MESSAGE_NOT_FOUND");
    }

    // AC7

    @Test
    void markersInHeadersBodyAndFilenameAppearOnlyUnderUntrusted() throws Exception {
        MimeMessage m = withAttachment();
        m.setFrom(new InternetAddress("a@example.org", MARKER + "-from"));
        m.setSubject(MARKER + "-subject");
        m.setHeader("X-Custom-" + "A", MARKER + "-xa");
        m.setHeader("In-Reply-To", "<" + MARKER + "@x>");
        MimeMultipart mp = (MimeMultipart) m.getContent();
        mp.getBodyPart(0).setText(MARKER + "-body");
        m.setContent(mp);
        m.saveChanges();
        String h = deliver(m);
        Map<String, Object> out = ok(call(h, Map.of("include_headers", true)));
        List<String> leaks = new ArrayList<>();
        walk(out, "$", leaks);
        assertThat(leaks).isEmpty();
        assertThat(out.toString()).contains(MARKER + "-from").contains(MARKER + "-subject").contains(MARKER + "-xa")
                .contains(MARKER + "-body").contains(MARKER + ".pdf");
    }

    @SuppressWarnings("unchecked")
    static void walk(Object node, String path, List<String> leaks) {
        if (node instanceof Map<?, ?> map) {
            map.forEach((k, v) -> walk(v, path + "." + k, leaks));
        } else if (node instanceof List<?> list) {
            for (int i = 0; i < list.size(); i++) {
                walk(list.get(i), path + "[" + i + "]", leaks);
            }
        } else if (node instanceof String s && s.contains(MARKER) && !path.contains("untrusted")) {
            leaks.add(path);
        }
    }

    // AC8 (through the validator the SDK adapter installs)

    @Test
    void maxCharsAboveTheCapIsInvalidArgumentWithMax() {
        ValidatingToolHandler v = new ValidatingToolHandler(GetMessageTool.definition(100000), tool);
        ToolOutcome o = v.handle(new ToolCallContext(new AccountContext(A), "rid"),
                Map.of("account", acctHandle, "message", "m", "max_chars", 100001));
        Map<String, Object> e = error(o);
        assertThat(e).containsEntry("code", "INVALID_ARGUMENT");
        assertThat(e.get("details")).isEqualTo(Map.of("field", "max_chars", "reason", "out_of_range", "max", 100000));
    }

    // #138: offset has a published maximum (Integer.MAX_VALUE), enforced before any IMAP I/O

    @Test
    @SuppressWarnings("unchecked")
    void offsetPublishesTheIntMaximum() {
        Map<String, Object> props = (Map<String, Object>) GetMessageTool.definition(100000).inputSchema()
                .get("properties");
        assertThat(((Map<String, Object>) props.get("offset")).get("maximum")).isEqualTo(Integer.MAX_VALUE);
    }

    @Test
    void offsetsAboveTheMaximumAreInvalidArgumentBeforeAnyImapIo() {
        ValidatingToolHandler v = new ValidatingToolHandler(GetMessageTool.definition(100000), tool);
        List<Object> bad = List.of(2147483648L, new java.math.BigInteger("9223372036854775808"),
                new java.math.BigInteger("18446744073709551616"), 1e300, 2147483648.0, Long.MAX_VALUE);
        for (Object offset : bad) {
            Map<String, Object> e = error(v.handle(new ToolCallContext(new AccountContext(A), "rid"),
                    Map.of("account", acctHandle, "message", "m", "offset", offset)));
            assertThat(e).containsEntry("code", "INVALID_ARGUMENT");
            assertThat(e.get("details")).as(String.valueOf(offset))
                    .isEqualTo(Map.of("field", "offset", "reason", "out_of_range", "max", Integer.MAX_VALUE));
        }
        assertThat(pool.createdCount()).isZero();
        assertThat(traceLines()).isEmpty();
    }

    @Test
    @SuppressWarnings("unchecked")
    void offsetAtTheMaximumOnAShortMessageIsAnEmptyWindowAndTheStoreIsReturned() throws Exception {
        String h = deliver(plain("short", "tiny"));
        ValidatingToolHandler v = new ValidatingToolHandler(GetMessageTool.definition(100000), tool);
        ToolOutcome o = v.handle(new ToolCallContext(new AccountContext(A), "rid"),
                Map.of("account", acctHandle, "message", h, "offset", Integer.MAX_VALUE));
        Map<String, Object> b = body(ok(o));
        assertThat(b).containsEntry("returned_chars", 0).containsEntry("text", "").containsEntry("truncated", false);
        assertThat(pool.destroyedCount()).isZero();
        assertThat(pool.createdCount()).isEqualTo(1);
    }

    @Test
    void configuredCapLowersThePublishedMaximumAndTheValidatedLimit() {
        @SuppressWarnings("unchecked")
        Map<String, Object> props = (Map<String, Object>) GetMessageTool.definition(5000).inputSchema()
                .get("properties");
        assertThat(((Map<?, ?>) props.get("max_chars")).get("maximum")).isEqualTo(5000);
        ValidatingToolHandler v = new ValidatingToolHandler(GetMessageTool.definition(5000), tool);
        Map<String, Object> e = error(v.handle(new ToolCallContext(new AccountContext(A), "rid"),
                Map.of("account", acctHandle, "message", "m", "max_chars", 5001)));
        assertThat(((Map<?, ?>) e.get("details")).get("max")).isEqualTo(5000);
    }
}
