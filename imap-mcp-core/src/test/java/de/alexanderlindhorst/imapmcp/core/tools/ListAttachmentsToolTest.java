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

class ListAttachmentsToolTest {

    static final TenantId A = new TenantId("tenant-a");
    static final TenantId B = new TenantId("tenant-b");
    static final AccountId ACCT = new AccountId("acct");
    static final AccountId OTHER = new AccountId("other");
    static final String MARKER = "FILENAME-MARKER-9f3";
    static final KeyRing KEYS = new KeyRing(1, Map.of(1, new byte[32]));

    @RegisterExtension
    static final GreenMailExtension GREEN_MAIL = new GreenMailExtension(ServerSetupTest.IMAPS.dynamicPort());

    final MessageHandles handles = new MessageHandles(KEYS);
    final ListAppender<ILoggingEvent> trace = new ListAppender<>();
    Logger traceLogger;
    ImapSessionFactory sessions;
    StorePool pool;
    InMemoryAccountRepository repo;
    ListAttachmentsTool tool;
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
        tool = new ListAttachmentsTool(new AccountArgument(repo), new MessageLocator(handles),
                new StoreLeases(pool, Duration.ofSeconds(10)));
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

    static MimeBodyPart attachment(String type, String name, byte[] data, String disposition) throws Exception {
        MimeBodyPart p = new MimeBodyPart();
        p.setDataHandler(new jakarta.activation.DataHandler(new ByteArrayDataSource(data, type)));
        p.setDisposition(disposition);
        if (name != null) {
            p.setFileName(name);
        }
        return p;
    }

    MimeMessage withAttachments() throws Exception {
        MimeMultipart related = new MimeMultipart("related");
        MimeBodyPart html = new MimeBodyPart();
        html.setText("<p>hi <img src=\"cid:logo\"></p>", "UTF-8", "html");
        related.addBodyPart(html);
        MimeBodyPart img = attachment("image/png", "logo.png", new byte[300], "inline");
        img.setContentID("<logo>");
        related.addBodyPart(img);
        MimeMultipart mixed = new MimeMultipart("mixed");
        MimeBodyPart rel = new MimeBodyPart();
        rel.setContent(related);
        mixed.addBodyPart(rel);
        mixed.addBodyPart(attachment("application/pdf", MARKER + ".pdf", new byte[3000], "attachment"));
        mixed.addBodyPart(attachment("application/zip", "a.zip", new byte[90], "attachment"));
        return message(mixed);
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

    Store client() throws Exception {
        java.util.Properties props = new java.util.Properties();
        props.put("mail.imaps.ssl.trust", "*");
        props.put("mail.imaps.ssl.checkserveridentity", "false");
        Store s = Session.getInstance(props).getStore("imaps");
        s.connect("127.0.0.1", GREEN_MAIL.getImaps().getPort(), "user", "secret");
        return s;
    }

    /** Delivers into {@code folder} (created if needed) and mints a handle for the message just delivered. */
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

    ToolOutcome call(TenantId tenant, String account, String message) {
        return tool.handle(new ToolCallContext(new AccountContext(tenant), "rid"),
                Map.of("account", account, "message", message));
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

    // AC1

    @Test
    @SuppressWarnings("unchecked")
    void listsTwoAttachmentsAndInlineImage() throws Exception {
        String h = deliver("INBOX", withAttachments(), A, ACCT);
        Map<String, Object> out = ok(call(A, acctHandle, h));
        assertThat(out).containsEntry("account", acctHandle).containsEntry("handle", h)
                .containsEntry("content_notice", UntrustedText.CONTENT_NOTICE)
                .containsEntry("total", 3).containsEntry("truncated", false);
        List<Map<String, Object>> list = (List<Map<String, Object>>) out.get("attachments");
        assertThat(list).extracting(m -> m.get("part")).containsExactly("1.2", "2", "3");
        assertThat(list).extracting(m -> m.get("inline")).containsExactly(true, false, false);
        assertThat(((Map<String, Object>) list.get(1).get("untrusted"))).containsEntry("filename", MARKER + ".pdf")
                .containsEntry("content_type", "application/pdf");
        assertThat((Long) list.get(1).get("size")).isBetween(2900L, 3300L);
    }

    // AC2

    @Test
    void fetchesOnlyBodystructureViaExamineAndLeavesSeenUntouched() throws Exception {
        String h = deliver("INBOX", withAttachments(), A, ACCT);
        trace.list.clear();
        ok(call(A, acctHandle, h));
        List<String> lines = traceLines();
        assertThat(lines).anyMatch(l -> l.contains("EXAMINE"));
        assertThat(lines).anyMatch(l -> l.contains("BODYSTRUCTURE") && l.contains("FETCH"));
        assertThat(lines).noneMatch(l -> l.contains("BODY[") || l.contains("BODY.PEEK[") || l.contains("RFC822")
                || l.contains(" SELECT ") || l.contains(" STORE ") || l.contains("EXPUNGE"));
        try (Store s = client()) {
            Folder f = s.getFolder("INBOX");
            f.open(Folder.READ_ONLY);
            assertThat(f.getMessage(f.getMessageCount()).isSet(Flags.Flag.SEEN)).isFalse();
            f.close(false);
        }
    }

    // AC3

    @Test
    void tamperedHandleIsInvalidHandleWithoutAnyLeaseOrImapCommand() throws Exception {
        String h = deliver("INBOX", withAttachments(), A, ACCT);
        long created = pool.createdCount();
        trace.list.clear();
        String tampered = h.substring(0, h.length() - 2) + (h.endsWith("AA") ? "BB" : "AA");
        Map<String, Object> e = error(call(A, acctHandle, tampered));
        assertThat(e).containsEntry("code", "INVALID_HANDLE");
        assertThat(pool.createdCount()).isEqualTo(created);
        assertThat(traceLines()).isEmpty();
    }

    @Test
    void unknownAccountWinsOverInvalidHandle() {
        Map<String, Object> e = error(call(A, "acc_aaaaaaaaaaaaaaaaaaaaaaaaaa", "m1.garbage"));
        assertThat(e).containsEntry("code", "ACCOUNT_NOT_FOUND");
        assertThat(pool.createdCount()).isZero();
    }

    @Test
    void deletedFolderIsStaleFolderMissing() throws Exception {
        String h = deliver("Box", withAttachments(), A, ACCT);
        try (Store s = client()) {
            s.getFolder("Box").delete(true);
        }
        Map<String, Object> e = error(call(A, acctHandle, h));
        assertThat(e).containsEntry("code", "STALE_REFERENCE");
        assertThat(e.get("details")).isEqualTo(Map.of("reason", "folder_missing"));
    }

    @Test
    void recreatedFolderIsStaleUidvalidityChanged() throws Exception {
        String h = deliver("Box", withAttachments(), A, ACCT);
        try (Store s = client()) {
            s.getFolder("Box").delete(true);
        }
        // a different UIDVALIDITY must be observable for the same folder name
        deliver("Box", withAttachments(), A, ACCT);
        Map<String, Object> e = error(call(A, acctHandle, h));
        assertThat(e).containsEntry("code", "STALE_REFERENCE");
        assertThat(e.get("details")).isEqualTo(Map.of("reason", "uidvalidity_changed"));
    }

    @Test
    void expungedMessageIsMessageNotFound() throws Exception {
        String h = deliver("INBOX", withAttachments(), A, ACCT);
        try (Store s = client()) {
            Folder f = s.getFolder("INBOX");
            f.open(Folder.READ_WRITE);
            f.getMessage(f.getMessageCount()).setFlag(Flags.Flag.DELETED, true);
            f.close(true);
        }
        assertThat(error(call(A, acctHandle, h))).containsEntry("code", "MESSAGE_NOT_FOUND");
    }

    // AC4

    @Test
    void otherTenantsContextGetsInvalidHandleIdenticalToTampered() throws Exception {
        String h = deliver("INBOX", withAttachments(), A, ACCT);
        String bAcct = InMemoryAccountRepository.handle(B, ACCT);
        Map<String, Object> cross = error(call(B, bAcct, h));
        Map<String, Object> tampered = error(call(B, bAcct, h.substring(0, h.length() - 2) + "AA"));
        assertThat(cross).containsEntry("code", "INVALID_HANDLE").isEqualTo(tampered);
    }

    @Test
    void handleOfAnotherAccountOfTheSameTenantIsInvalid() throws Exception {
        String h = deliver("INBOX", withAttachments(), A, ACCT);
        Map<String, Object> cross = error(call(A, otherHandle, h));
        assertThat(cross).containsEntry("code", "INVALID_HANDLE");
        assertThat(cross).isEqualTo(error(call(A, otherHandle, h.substring(0, h.length() - 2) + "AA")));
    }

    // AC5

    @Test
    @SuppressWarnings("unchecked")
    void filenameMarkerOnlyUnderUntrusted() throws Exception {
        String h = deliver("INBOX", withAttachments(), A, ACCT);
        Map<String, Object> out = ok(call(A, acctHandle, h));
        Map<String, Object> copy = new HashMap<>(out);
        List<Map<String, Object>> list = (List<Map<String, Object>>) copy.remove("attachments");
        assertThat(copy.toString()).doesNotContain(MARKER);
        for (Map<String, Object> entry : list) {
            Map<String, Object> rest = new HashMap<>(entry);
            Map<String, Object> untrusted = (Map<String, Object>) rest.remove("untrusted");
            assertThat(rest.toString()).doesNotContain(MARKER);
            assertThat(untrusted).containsKeys("filename", "content_type");
        }
        assertThat(list.toString()).contains(MARKER);
    }
}
