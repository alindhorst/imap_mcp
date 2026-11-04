package de.alexanderlindhorst.imapmcp.core.tools.write;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
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
import de.alexanderlindhorst.imapmcp.core.error.ErrorCode;
import de.alexanderlindhorst.imapmcp.core.handle.MessageHandles;
import de.alexanderlindhorst.imapmcp.core.handle.MessageRef;
import de.alexanderlindhorst.imapmcp.core.imap.CapabilityCache;
import de.alexanderlindhorst.imapmcp.core.imap.ImapSessionFactory;
import de.alexanderlindhorst.imapmcp.core.imap.ImapTimeouts;
import de.alexanderlindhorst.imapmcp.core.imap.pool.ImapLease;
import de.alexanderlindhorst.imapmcp.core.imap.pool.LeaseTestSeams;
import de.alexanderlindhorst.imapmcp.core.imap.pool.PoolSettings;
import de.alexanderlindhorst.imapmcp.core.imap.pool.StoreLeases;
import de.alexanderlindhorst.imapmcp.core.imap.pool.StorePool;
import de.alexanderlindhorst.imapmcp.core.tenancy.AccountContext;
import de.alexanderlindhorst.imapmcp.core.tenancy.AccountId;
import de.alexanderlindhorst.imapmcp.core.tenancy.TenantId;
import de.alexanderlindhorst.imapmcp.core.tool.DescriptionRules;
import de.alexanderlindhorst.imapmcp.core.tool.ToolCallContext;
import de.alexanderlindhorst.imapmcp.core.tool.ToolOutcome;
import de.alexanderlindhorst.imapmcp.core.tool.ValidatingToolHandler;
import de.alexanderlindhorst.imapmcp.core.tools.AccountArgument;
import de.alexanderlindhorst.imapmcp.core.tools.MessageLocator;
import jakarta.mail.Flags;
import jakarta.mail.Folder;
import jakarta.mail.Message;
import jakarta.mail.Session;
import jakarta.mail.internet.MimeMessage;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;
import org.eclipse.angus.mail.imap.IMAPFolder;
import org.eclipse.angus.mail.imap.IMAPStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

/** move_messages without MOVE (GreenMail, MOVE forced off): COPY, STORE \\Deleted, UID EXPUNGE of the copied UIDs. */
class MoveFallbackTest {

    private static final TenantId T1 = new TenantId("t1");
    private static final TenantId T2 = new TenantId("t2");
    private static final String USER = "fallbackuser";

    private GreenMail greenMail;
    private GreenMailUser user;
    private MailAccount account;
    private ImapSessionFactory sessions;
    private StorePool pool;
    private StoreLeases leases;
    private MessageHandles handles;
    private WritePreflight preflight;
    private MoveMessagesTool tool;
    private FolderGroupRunner.StepHook hook = (p, g, c) -> { };
    private final ListAppender<ILoggingEvent> callLog = new ListAppender<>();
    private Logger callLogger;
    private ImapLease lastLease;
    private ToolCallContext lastCtx;
    private ValidatingToolHandler handler;
    private int counter;
    private final ListAppender<ILoggingEvent> trace = new ListAppender<>();
    private Logger traceLogger;
    private String accountHandle1;
    private String accountHandle2;


    @BeforeEach
    void setUp() throws Exception {
        greenMail = new GreenMail(ServerSetupTest.IMAPS.dynamicPort());
        greenMail.start();
        user = greenMail.setUser(USER + "@localhost", USER, "pw");
        account = new MailAccount(new AccountId("a1"), "Label", null, "127.0.0.1", greenMail.getImaps().getPort(),
                Security.IMPLICIT_TLS, Flavor.GENERIC, USER, new SecretRef("env:X"));
        var repo = new InMemoryAccountRepository(Map.of(T1, List.of(account), T2, List.of(account)));
        accountHandle1 = InMemoryAccountRepository.handle(T1, account.id());
        accountHandle2 = InMemoryAccountRepository.handle(T2, account.id());
        sessions = new ImapSessionFactory(ImapTimeouts.DEFAULTS, p -> {
            p.put("mail.imaps.ssl.trust", "*");
            p.put("mail.imaps.ssl.checkserveridentity", "false");
            return p;
        }, true);
        pool = new StorePool(sessions, (t, a) -> Optional.of(new Secret("pw".toCharArray())), new CapabilityCache(),
                k -> account, PoolSettings.DEFAULTS);
        leases = new StoreLeases(pool, Duration.ofSeconds(10));
        handles = new MessageHandles(new KeyRing(1, Map.of(1, new byte[32])));
        preflight = new WritePreflight(new AccountArgument(repo), new MessageLocator(handles));
        tool = new MoveMessagesTool(preflight, leases, handles, Clock.systemUTC(),
                new FolderGroupRunner(Clock.systemUTC(), (p, g, c) -> hook.at(p, g, c)));
        callLogger = (Logger) LoggerFactory.getLogger(WriteCallLog.class);
        callLogger.addAppender(callLog);
        callLog.start();
        traceLogger = (Logger) LoggerFactory.getLogger("imapmcp.imap.trace");
        traceLogger.setLevel(Level.DEBUG);
        traceLogger.addAppender(trace);
        trace.start();
        createFolder("Source2");
        createFolder("Target");
        deliver("INBOX", "a");
        deliver("INBOX", "b");
        deliver("INBOX", "c");
        deliver("Source2", "d");
    }

    @AfterEach
    void tearDown() {
        traceLogger.detachAppender(trace);
        callLogger.detachAppender(callLog);
        pool.close();
        sessions.close();
        greenMail.stop();
    }

    // ---- helpers ----

    private void createFolder(String name) throws Exception {
        greenMail.getManagers().getImapHostManager().createMailbox(user, name);
    }

    private void deliver(String folder, String subject) throws Exception {
        MimeMessage m = new MimeMessage((Session) null);
        m.setFrom("a@localhost");
        m.setSubject(subject);
        m.setText("body " + subject);
        var f = greenMail.getManagers().getImapHostManager().getFolder(user, folder);
        f.appendMessage(m, new Flags(), new java.util.Date());
    }

    private IMAPStore direct() throws Exception {
        Properties p = new Properties();
        p.put("mail.imaps.ssl.trust", "*");
        p.put("mail.imaps.ssl.checkserveridentity", "false");
        IMAPStore s = (IMAPStore) Session.getInstance(p).getStore("imaps");
        s.connect("127.0.0.1", greenMail.getImaps().getPort(), USER, "pw");
        return s;
    }

    /** uid -> "subject|flags" of a folder, read on a separate connection with EXAMINE and PEEK semantics. */
    private Map<Long, String> snapshot(String folder) throws Exception {
        IMAPStore s = direct();
        try {
            IMAPFolder f = (IMAPFolder) s.getFolder(folder);
            f.open(Folder.READ_ONLY);
            Map<Long, String> out = new LinkedHashMap<>();
            for (Message m : f.getMessages()) {
                Flags flags = new Flags(m.getFlags());
                flags.remove(Flags.Flag.RECENT); // SELECT clears \\Recent; not part of the contract (SPEC 5.4)
                out.put(f.getUID(m), m.getSubject() + "|" + flags);
            }
            long ignored = f.getUIDValidity();
            f.close(false);
            return out;
        } finally {
            s.close();
        }
    }

    private long validity(String folder) throws Exception {
        IMAPStore s = direct();
        try {
            IMAPFolder f = (IMAPFolder) s.getFolder(folder);
            f.open(Folder.READ_ONLY);
            long v = f.getUIDValidity();
            f.close(false);
            return v;
        } finally {
            s.close();
        }
    }

    private String handle(TenantId t, String folder, long uid) throws Exception {
        return handles.encode(t, account.id(), new MessageRef(folder, validity(folder), uid));
    }

    /** One move_messages call on a direct connection whose lease reports UIDPLUS without MOVE. */
    private ToolOutcome call(List<String> hs) throws Exception {
        ToolCallContext ctx = new ToolCallContext(new AccountContext(T1), "rid-" + (++counter));
        var ok = (WritePreflight.Result.Ok) preflight.check(ctx, accountHandle1, hs, null);
        var key = de.alexanderlindhorst.imapmcp.core.account.PoolKey.of(new AccountContext(T1),
                new de.alexanderlindhorst.imapmcp.core.account.ResolvedAccount(T1, account, accountHandle1));
        IMAPStore s = (IMAPStore) sessions.sessionFor(key, account).getStore("imaps"); // traced session
        s.connect("127.0.0.1", greenMail.getImaps().getPort(), USER, "pw");
        try {
            trace.list.clear();
            var lease = LeaseTestSeams.lease(s, ctx);
            LeaseTestSeams.forceCapabilities(lease, false, true);
            lastLease = lease;
            lastCtx = ctx;
            return tool.onLease(lease, ctx, ok, accountHandle1, hs, "Target", null);
        } finally {
            s.close();
        }
    }

    private boolean broken() throws Exception {
        var f = ImapLease.class.getDeclaredField("broken");
        f.setAccessible(true);
        return ((java.util.concurrent.atomic.AtomicBoolean) f.get(lastLease)).get();
    }

    private String callLine() {
        return callLog.list.get(callLog.list.size() - 1).getFormattedMessage();
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> ok(ToolOutcome o) {
        if (o instanceof ToolOutcome.Failure f) {
            throw new AssertionError("unexpected failure " + f.error().toMap());
        }
        return (Map<String, Object>) ((ToolOutcome.Success) o).structured();
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> results(Map<String, Object> out) {
        return (List<Map<String, Object>>) out.get("results");
    }

    private String wire() {
        return String.join("\n", trace.list.stream().map(ILoggingEvent::getFormattedMessage).toList());
    }

    // ---- tests ----

    @Test
    void movesWithCopyStoreUidExpungeOnlyAndReportsTheCopyFallbackPath() throws Exception {
        List<Long> uids = new ArrayList<>(snapshot("INBOX").keySet());
        String h1 = handle(T1, "INBOX", uids.get(0));
        String h2 = handle(T1, "INBOX", uids.get(2));

        Map<String, Object> out = ok(call(List.of(h1, h2)));
        String w = wire(); // before the verification connections add to the trace

        assertThat(results(out)).extracting(r -> r.get("status")).containsOnly("ok");
        Map<Long, String> target = snapshot("Target");
        long tv = validity("Target");
        for (Map<String, Object> r : results(out)) {
            var ref = ((MessageHandles.Decoded.Ok) handles.decode(T1, account.id(), (String) r.get("new_handle"))).ref();
            assertThat(ref.folder()).isEqualTo("Target");
            assertThat(ref.uidValidity()).isEqualTo(tv);
            assertThat(target).containsKey(ref.uid());
        }
        assertThat(snapshot("INBOX").keySet()).containsExactly(uids.get(1));
        int copy = w.indexOf("UID COPY");
        int store = w.indexOf("UID STORE");
        int expunge = w.indexOf("UID EXPUNGE");
        assertThat(copy).isNotNegative();
        assertThat(store).isGreaterThan(copy);
        assertThat(expunge).isGreaterThan(store);
        assertThat(w).contains("+FLAGS.SILENT (\\Deleted)").doesNotContain("UID MOVE")
                .doesNotContainPattern("(?m)^\\S+ EXPUNGE").doesNotContainPattern("(?m)^\\S+ CLOSE");
        assertThat(callLine()).contains("path=copy_fallback");
    }

    @Test
    void aForeignDeletedMessageInTheSourceSurvivesWithItsUid() throws Exception {
        var inbox = greenMail.getManagers().getImapHostManager().getFolder(user, "INBOX");
        List<Long> uids = new ArrayList<>(snapshot("INBOX").keySet());
        inbox.setFlags(new Flags(Flags.Flag.DELETED), true, uids.get(2), null, false);

        ok(call(List.of(handle(T1, "INBOX", uids.get(0)))));

        Map<Long, String> after = snapshot("INBOX");
        assertThat(after.keySet()).containsExactly(uids.get(1), uids.get(2));
        assertThat(after.get(uids.get(2))).contains("\\Deleted");
    }

    @Test
    void anAddressedDeletedMessageMovesAndArrivesWithDeletedSet() throws Exception {
        var inbox = greenMail.getManagers().getImapHostManager().getFolder(user, "INBOX");
        List<Long> uids = new ArrayList<>(snapshot("INBOX").keySet());
        inbox.setFlags(new Flags(Flags.Flag.DELETED), true, uids.get(0), null, false);

        Map<String, Object> out = ok(call(List.of(handle(T1, "INBOX", uids.get(0)))));

        assertThat(results(out).get(0).get("status")).isEqualTo("ok");
        assertThat(snapshot("INBOX").keySet()).containsExactly(uids.get(1), uids.get(2));
        assertThat(snapshot("Target").values()).singleElement().satisfies(v -> assertThat(v).contains("\\Deleted"));
    }

    @Test
    void aFaultAfterCopyBeforeStoreLeavesTheGroupUnknownAndTheLeaseBroken() throws Exception {
        hook = (p, g, c) -> {
            if (p == FolderGroupRunner.HookPoint.BEFORE_WRITE && c == 1) {
                throw new jakarta.mail.FolderClosedException(null, "lost");
            }
        };
        List<Long> uids = new ArrayList<>(snapshot("INBOX").keySet());

        Map<String, Object> out = ok(call(List.of(handle(T1, "INBOX", uids.get(0)))));

        assertThat(results(out).get(0).get("status")).isEqualTo("unknown");
        assertThat(broken()).isTrue();
        assertThat(wire()).contains("UID COPY").doesNotContain("UID STORE").doesNotContain("UID EXPUNGE");
        assertThat(snapshot("INBOX")).containsKey(uids.get(0)); // the copy in the target is possible, nothing lost
    }

    @Test
    void theBudgetIsNotConsultedBetweenCopyStoreAndExpunge() throws Exception {
        // a deadline already inside the 15 s budget would stop a group before its first command only
        List<Long> uids = new ArrayList<>(snapshot("INBOX").keySet());
        List<Integer> commands = new ArrayList<>();
        hook = (p, g, c) -> {
            if (p == FolderGroupRunner.HookPoint.AFTER_WRITE) {
                commands.add(c);
            }
        };
        ok(call(List.of(handle(T1, "INBOX", uids.get(0)))));
        assertThat(commands).containsExactly(0, 1, 2);
    }
}
