package de.alexanderlindhorst.imapmcp.core.tools;

import com.icegreen.greenmail.store.MailFolder;
import com.icegreen.greenmail.user.GreenMailUser;
import com.icegreen.greenmail.util.GreenMail;
import com.icegreen.greenmail.util.ServerSetupTest;
import de.alexanderlindhorst.imapmcp.core.account.InMemoryAccountRepository;
import de.alexanderlindhorst.imapmcp.core.account.MailAccount;
import de.alexanderlindhorst.imapmcp.core.account.MailAccount.Flavor;
import de.alexanderlindhorst.imapmcp.core.account.MailAccount.Security;
import de.alexanderlindhorst.imapmcp.core.account.Secret;
import de.alexanderlindhorst.imapmcp.core.account.SecretRef;
import de.alexanderlindhorst.imapmcp.core.crypto.KeyRing;
import de.alexanderlindhorst.imapmcp.core.error.ToolError;
import de.alexanderlindhorst.imapmcp.core.handle.MessageHandles;
import de.alexanderlindhorst.imapmcp.core.imap.CapabilityCache;
import de.alexanderlindhorst.imapmcp.core.imap.ImapSessionFactory;
import de.alexanderlindhorst.imapmcp.core.imap.ImapTimeouts;
import de.alexanderlindhorst.imapmcp.core.imap.pool.PoolSettings;
import de.alexanderlindhorst.imapmcp.core.imap.pool.StoreLeases;
import de.alexanderlindhorst.imapmcp.core.imap.pool.StorePool;
import de.alexanderlindhorst.imapmcp.core.search.SearchCursorCodec;
import de.alexanderlindhorst.imapmcp.core.search.SearchTermMapper;
import de.alexanderlindhorst.imapmcp.core.tenancy.AccountContext;
import de.alexanderlindhorst.imapmcp.core.tenancy.AccountId;
import de.alexanderlindhorst.imapmcp.core.tenancy.TenantId;
import de.alexanderlindhorst.imapmcp.core.tool.ToolCallContext;
import de.alexanderlindhorst.imapmcp.core.tool.ToolOutcome;
import de.alexanderlindhorst.imapmcp.core.tool.ValidatingToolHandler;
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
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;

/**
 * GreenMail + real pool + the search_messages tool for two tenants that both own an account with the id "a1"
 * on the same mailbox (public: the SDK test in core.mcp reuses it).
 */
public final class SearchEnv implements AutoCloseable {

    public static final TenantId T1 = new TenantId("t1");
    public static final TenantId T2 = new TenantId("t2");
    public static final String USER = "searchuser";

    /** Settable test clock. */
    public static final class TestClock extends Clock {
        public volatile Instant now = Instant.parse("2026-03-05T10:00:07.900Z");

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }

    public final GreenMail greenMail;
    public final GreenMailUser user;
    public final TestClock clock = new TestClock();
    public final KeyRing keys = new KeyRing(1, Map.of(1, new byte[32]));
    public final MessageHandles handles = new MessageHandles(keys);
    public final SearchCursorCodec cursors = new SearchCursorCodec(keys);
    public final SearchMessagesTool tool;
    public final String handleT1;
    public final String handleT2;
    public final MailAccount account;
    private final ImapSessionFactory sessions;
    private final StorePool pool;
    private int counter;

    public SearchEnv() {
        this(SearchMessagesTool.DEFAULT_SINCE_MAX_CANDIDATES);
    }

    public SearchEnv(int sinceMaxCandidates) {
        greenMail = new GreenMail(ServerSetupTest.IMAPS.dynamicPort());
        greenMail.start();
        user = greenMail.setUser(USER + "@localhost", USER, "pw");
        account = new MailAccount(new AccountId("a1"), "Label", null, "127.0.0.1", greenMail.getImaps().getPort(),
                Security.IMPLICIT_TLS, Flavor.GENERIC, USER, new SecretRef("env:X"));
        InMemoryAccountRepository repo = new InMemoryAccountRepository(Map.of(T1, List.of(account), T2, List.of(account)));
        handleT1 = InMemoryAccountRepository.handle(T1, account.id());
        handleT2 = InMemoryAccountRepository.handle(T2, account.id());
        sessions = new ImapSessionFactory(ImapTimeouts.DEFAULTS, p -> {
            p.put("mail.imaps.ssl.trust", "*");
            p.put("mail.imaps.ssl.checkserveridentity", "false");
            return p;
        }, true);
        pool = new StorePool(sessions, (t, a) -> Optional.of(new Secret("pw".toCharArray())), new CapabilityCache(),
                k -> account, PoolSettings.DEFAULTS);
        tool = new SearchMessagesTool(new AccountArgument(repo), new StoreLeases(pool, Duration.ofSeconds(10)),
                new SearchTermMapper(), cursors, handles, clock, SearchMessagesTool.SINCE_OVERLAP,
                sinceMaxCandidates);
    }

    public ToolOutcome call(TenantId tenant, Map<String, Object> args) {
        return new ValidatingToolHandler(SearchMessagesTool.DEFINITION, tool)
                .handle(new ToolCallContext(new AccountContext(tenant), "rid-" + (++counter)), args);
    }

    @SuppressWarnings("unchecked")
    public Map<String, Object> ok(TenantId tenant, Map<String, Object> args) {
        ToolOutcome o = call(tenant, args);
        if (o instanceof ToolOutcome.Failure f) {
            throw new AssertionError("unexpected failure " + f.error().toMap());
        }
        return (Map<String, Object>) ((ToolOutcome.Success) o).structured();
    }

    public ToolError error(TenantId tenant, Map<String, Object> args) {
        ToolOutcome o = call(tenant, args);
        if (o instanceof ToolOutcome.Success) {
            throw new AssertionError("unexpected success");
        }
        return ((ToolOutcome.Failure) o).error();
    }

    // ---- seeding ----

    public MimeMessage message(String fromName, String fromAddr, String subject) throws Exception {
        MimeMessage m = new MimeMessage((Session) null);
        m.setFrom(new InternetAddress(fromAddr, fromName, "UTF-8"));
        m.setRecipient(Message.RecipientType.TO, new InternetAddress(USER + "@localhost"));
        m.setSubject(subject, "UTF-8");
        m.setText("body of " + subject);
        return m;
    }

    public void deliver(int n) throws Exception {
        for (int i = 0; i < n; i++) {
            user.deliver(message("Sender " + i, "s" + i + "@example.org", "Subject " + i));
        }
    }

    /** Appends one message to INBOX with an explicit INTERNALDATE (like IMAP APPEND with a date-time). */
    public void deliverAt(Instant received, String subject) throws Exception {
        MailFolder inbox = greenMail.getManagers().getImapHostManager().getInbox(user);
        inbox.appendMessage(message("Sender", "s@example.org", subject), new Flags(), java.util.Date.from(received));
    }

    public MimeMessage withAttachment(MimeMessage base) throws Exception {
        MimeMultipart mp = new MimeMultipart();
        MimeBodyPart text = new MimeBodyPart();
        text.setText("see attachment");
        mp.addBodyPart(text);
        MimeBodyPart att = new MimeBodyPart();
        att.setDataHandler(new jakarta.activation.DataHandler(new ByteArrayDataSource(new byte[] {1, 2, 3}, "application/pdf")));
        att.setFileName("report.pdf");
        att.setDisposition("attachment");
        mp.addBodyPart(att);
        base.setContent(mp);
        base.saveChanges();
        return base;
    }

    public void storeIn(String folder, int n) throws Exception {
        MailFolder f = greenMail.getManagers().getImapHostManager().getFolder(user, folder);
        for (int i = 0; i < n; i++) {
            f.store(message("Sender " + i, "s" + i + "@example.org", "Subject " + i));
        }
    }

    public void createFolder(String name) throws Exception {
        greenMail.getManagers().getImapHostManager().createMailbox(user, name);
    }

    public void deleteFolder(String name) throws Exception {
        greenMail.getManagers().getImapHostManager().deleteMailbox(user, name);
    }

    // ---- independent second connection ----

    public <T> T withInbox(int mode, java.util.function.Function<Folder, T> body) throws Exception {
        Properties props = new Properties();
        props.put("mail.imaps.ssl.trust", "*");
        props.put("mail.imaps.ssl.checkserveridentity", "false");
        try (Store st = Session.getInstance(props).getStore("imaps")) {
            st.connect("127.0.0.1", greenMail.getImaps().getPort(), USER, "pw");
            Folder f = st.getFolder("INBOX");
            f.open(mode);
            try {
                return body.apply(f);
            } finally {
                f.close(false);
            }
        }
    }

    public long unseenCount() throws Exception {
        return withInbox(Folder.READ_ONLY, f -> {
            try {
                long n = 0;
                for (Message m : f.getMessages()) {
                    if (!m.isSet(Flags.Flag.SEEN)) {
                        n++;
                    }
                }
                return n;
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        });
    }

    @Override
    public void close() {
        pool.close();
        sessions.close();
        greenMail.stop();
    }
}
