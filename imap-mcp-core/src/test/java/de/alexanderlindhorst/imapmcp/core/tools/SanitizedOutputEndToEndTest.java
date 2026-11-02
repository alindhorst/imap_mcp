package de.alexanderlindhorst.imapmcp.core.tools;

import static org.assertj.core.api.Assertions.assertThat;

import com.icegreen.greenmail.junit5.GreenMailExtension;
import com.icegreen.greenmail.util.ServerSetupTest;
import de.alexanderlindhorst.imapmcp.core.account.InMemoryAccountRepository;
import de.alexanderlindhorst.imapmcp.core.account.MailAccount;
import de.alexanderlindhorst.imapmcp.core.account.MailAccount.Flavor;
import de.alexanderlindhorst.imapmcp.core.account.MailAccount.Security;
import de.alexanderlindhorst.imapmcp.core.account.Secret;
import de.alexanderlindhorst.imapmcp.core.account.SecretRef;
import de.alexanderlindhorst.imapmcp.core.crypto.KeyRing;
import de.alexanderlindhorst.imapmcp.core.handle.MessageHandles;
import de.alexanderlindhorst.imapmcp.core.handle.MessageRef;
import de.alexanderlindhorst.imapmcp.core.imap.CapabilityCache;
import de.alexanderlindhorst.imapmcp.core.imap.ImapSessionFactory;
import de.alexanderlindhorst.imapmcp.core.imap.ImapTimeouts;
import de.alexanderlindhorst.imapmcp.core.imap.pool.PoolSettings;
import de.alexanderlindhorst.imapmcp.core.imap.pool.StoreLeases;
import de.alexanderlindhorst.imapmcp.core.imap.pool.StorePool;
import de.alexanderlindhorst.imapmcp.core.mime.BodyWindowReader;
import de.alexanderlindhorst.imapmcp.core.tenancy.AccountContext;
import de.alexanderlindhorst.imapmcp.core.tenancy.AccountId;
import de.alexanderlindhorst.imapmcp.core.tenancy.TenantId;
import de.alexanderlindhorst.imapmcp.core.tool.ToolCallContext;
import de.alexanderlindhorst.imapmcp.core.tool.ToolOutcome;
import jakarta.mail.Folder;
import jakarta.mail.Message;
import jakarta.mail.Session;
import jakarta.mail.Store;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeBodyPart;
import jakarta.mail.internet.MimeMessage;
import jakarta.mail.internet.MimeMultipart;
import jakarta.mail.internet.MimeUtility;
import jakarta.mail.util.ByteArrayDataSource;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.eclipse.angus.mail.imap.IMAPFolder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

/**
 * One end-to-end assertion per tool that email-derived strings reach the client sanitized: tag characters
 * (invisible to the human, readable by the model) and U+2028 in a search subject, a get_message header value and
 * body, and an attachment filename.
 */
class SanitizedOutputEndToEndTest {

    static final TenantId T = new TenantId("tenant-a");
    static final AccountId ACCT = new AccountId("acct");
    static final KeyRing KEYS = new KeyRing(1, Map.of(1, new byte[32]));
    /** Tag characters U+E0041 U+E0042 as a surrogate-pair string. */
    static final String TAGS = new String(new int[] {0xE0041, 0xE0042}, 0, 2);
    static final String LS = "\u2028";

    @RegisterExtension
    static final GreenMailExtension GREEN_MAIL = new GreenMailExtension(ServerSetupTest.IMAPS.dynamicPort());

    static final Session MAIL = Session.getInstance(new java.util.Properties());

    final MessageHandles handles = new MessageHandles(KEYS);
    ImapSessionFactory sessions;
    StorePool pool;
    String acctHandle;
    GetMessageTool getMessage;
    ListAttachmentsTool listAttachments;

    @BeforeEach
    void setUp() {
        GREEN_MAIL.setUser("user@localhost", "user", "secret");
        sessions = new ImapSessionFactory(ImapTimeouts.DEFAULTS, p -> {
            p.put("mail.imaps.ssl.trust", "*");
            p.put("mail.imaps.ssl.checkserveridentity", "false");
            return p;
        }, true);
        MailAccount account = new MailAccount(ACCT, "acct", null, "127.0.0.1", GREEN_MAIL.getImaps().getPort(),
                Security.IMPLICIT_TLS, Flavor.GENERIC, "user", new SecretRef("env:x"));
        InMemoryAccountRepository repo = new InMemoryAccountRepository(Map.of(T, List.of(account)));
        pool = new StorePool(sessions, (t, a) -> Optional.of(new Secret("secret".toCharArray())),
                new CapabilityCache(), k -> account, PoolSettings.DEFAULTS);
        StoreLeases leases = new StoreLeases(pool, Duration.ofSeconds(10));
        MessageLocator locator = new MessageLocator(handles);
        getMessage = new GetMessageTool(new AccountArgument(repo), locator, leases, new BodyWindowReader(), 100000);
        listAttachments = new ListAttachmentsTool(new AccountArgument(repo), locator, leases);
        acctHandle = InMemoryAccountRepository.handle(T, ACCT);
    }

    @AfterEach
    void tearDown() {
        pool.close();
        sessions.close();
    }

    private String deliver(MimeMessage msg) throws Exception {
        java.util.Properties props = new java.util.Properties();
        props.put("mail.imaps.ssl.trust", "*");
        props.put("mail.imaps.ssl.checkserveridentity", "false");
        try (Store s = Session.getInstance(props).getStore("imaps")) {
            s.connect("127.0.0.1", GREEN_MAIL.getImaps().getPort(), "user", "secret");
            IMAPFolder f = (IMAPFolder) s.getFolder("INBOX");
            f.open(Folder.READ_WRITE);
            f.appendMessages(new Message[] {msg});
            Message last = f.getMessage(f.getMessageCount());
            MessageRef ref = new MessageRef("INBOX", f.getUIDValidity(), f.getUID(last));
            f.close(false);
            return handles.encode(T, ACCT, ref);
        }
    }

    private static MimeMessage base(String subject) throws Exception {
        MimeMessage m = new MimeMessage(MAIL);
        m.setFrom(new InternetAddress("alice@example.org", "Alice"));
        m.setRecipients(Message.RecipientType.TO, "user@localhost");
        m.setSubject(subject, "UTF-8");
        return m;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> ok(ToolOutcome o) {
        assertThat(o).isInstanceOf(ToolOutcome.Success.class);
        return (Map<String, Object>) ((ToolOutcome.Success) o).structured();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> untrusted(Map<String, Object> out) {
        return (Map<String, Object>) out.get("untrusted");
    }

    @Test
    @SuppressWarnings("unchecked")
    void searchMessagesSubjectIsSanitized() throws Exception {
        MimeMessage m = base("inv" + TAGS + "oice" + LS + "x");
        m.setText("b", "UTF-8");
        m.saveChanges();
        deliver(m);
        SearchEnvLike env = new SearchEnvLike();
        Map<String, Object> out = env.search();
        List<Map<String, Object>> msgs = (List<Map<String, Object>>) out.get("messages");
        assertThat(untrusted(msgs.get(0))).containsEntry("subject", "invoice x");
    }

    @Test
    @SuppressWarnings("unchecked")
    void getMessageHeaderValueAndBodyAreSanitized() throws Exception {
        MimeMessage m = base("s");
        m.setHeader("X-Probe", MimeUtility.encodeText("v" + TAGS + "al" + LS + "ue", "UTF-8", "B"));
        m.setText("line" + TAGS + " one" + LS + "two\u009B\u007Fend", "UTF-8");
        m.saveChanges();
        String h = deliver(m);
        Map<String, Object> out = ok(getMessage.handle(new ToolCallContext(new AccountContext(T), "rid"),
                Map.of("account", acctHandle, "message", h, "include_headers", true)));
        List<Map<String, Object>> headers = (List<Map<String, Object>>) untrusted(out).get("headers");
        assertThat(headers).filteredOn(x -> "X-Probe".equals(x.get("name"))).extracting(x -> x.get("value"))
                .containsExactly("val ue");
        Map<String, Object> body = (Map<String, Object>) untrusted(out).get("body");
        assertThat(body.get("text")).isEqualTo("line one\ntwoend");
    }

    @Test
    @SuppressWarnings("unchecked")
    void listAttachmentsFilenameIsSanitized() throws Exception {
        MimeMultipart mixed = new MimeMultipart("mixed");
        MimeBodyPart text = new MimeBodyPart();
        text.setText("see attached", "UTF-8");
        mixed.addBodyPart(text);
        MimeBodyPart p = new MimeBodyPart();
        p.setDataHandler(new jakarta.activation.DataHandler(new ByteArrayDataSource(new byte[50], "application/pdf")));
        String encoded = rfc2231("re" + TAGS + "port" + LS + "x.pdf");
        p.setHeader("Content-Disposition", "attachment; filename*=UTF-8''" + encoded);
        mixed.addBodyPart(p);
        MimeMessage m = base("att");
        m.setContent(mixed);
        m.saveChanges();
        String h = deliver(m);
        Map<String, Object> out = ok(listAttachments.handle(new ToolCallContext(new AccountContext(T), "rid"),
                Map.of("account", acctHandle, "message", h)));
        List<Map<String, Object>> list = (List<Map<String, Object>>) out.get("attachments");
        assertThat(untrusted(list.get(0))).containsEntry("filename", "report x.pdf");
    }

    private static String rfc2231(String s) {
        StringBuilder sb = new StringBuilder();
        for (byte b : s.getBytes(StandardCharsets.UTF_8)) {
            int c = b & 0xFF;
            if ((c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9') || c == '.') {
                sb.append((char) c);
            } else {
                sb.append('%').append(String.format("%02X", c));
            }
        }
        return sb.toString();
    }

    /** search_messages over the same GreenMail server (own tool wiring, same account). */
    private final class SearchEnvLike {
        Map<String, Object> search() {
            MailAccount account = new MailAccount(ACCT, "acct", null, "127.0.0.1", GREEN_MAIL.getImaps().getPort(),
                    Security.IMPLICIT_TLS, Flavor.GENERIC, "user", new SecretRef("env:x"));
            InMemoryAccountRepository repo = new InMemoryAccountRepository(Map.of(T, List.of(account)));
            CapabilityCache caps = new CapabilityCache();
            var leases = new StoreLeases(pool, Duration.ofSeconds(10));
            var tool = new SearchMessagesTool(new AccountArgument(repo), leases,
                    new de.alexanderlindhorst.imapmcp.core.search.SearchTermMapper(),
                    new de.alexanderlindhorst.imapmcp.core.search.SearchCursorCodec(KEYS), handles,
                    java.time.Clock.systemUTC(), SearchMessagesTool.SINCE_OVERLAP,
                    SearchMessagesTool.DEFAULT_SINCE_MAX_CANDIDATES, caps);
            return ok(new de.alexanderlindhorst.imapmcp.core.tool.ValidatingToolHandler(
                    SearchMessagesTool.DEFINITION, tool).handle(new ToolCallContext(new AccountContext(T), "rid"),
                            Map.of("account", acctHandle)));
        }
    }
}
