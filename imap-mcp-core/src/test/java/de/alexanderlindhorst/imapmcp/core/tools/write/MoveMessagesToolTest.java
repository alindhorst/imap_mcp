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
import de.alexanderlindhorst.imapmcp.core.imap.pool.LeaseTestSeams;
import de.alexanderlindhorst.imapmcp.core.imap.pool.PoolSettings;
import de.alexanderlindhorst.imapmcp.core.imap.pool.StoreLeases;
import de.alexanderlindhorst.imapmcp.core.imap.pool.StorePool;
import de.alexanderlindhorst.imapmcp.core.testsupport.WireTrace;
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

/** move_messages end to end against GreenMail: UID MOVE only, new handles, retry, isolation, no side effects. */
class MoveMessagesToolTest {

    private static final TenantId T1 = new TenantId("t1");
    private static final TenantId T2 = new TenantId("t2");
    private static final String USER = "moveuser";

    private GreenMail greenMail;
    private GreenMailUser user;
    private MailAccount account;
    private ImapSessionFactory sessions;
    private StorePool pool;
    private StoreLeases leases;
    private MessageHandles handles;
    private WritePreflight preflight;
    private MoveMessagesTool tool;
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
        tool = new MoveMessagesTool(preflight, leases, handles, Clock.systemUTC());
        handler = new ValidatingToolHandler(MoveMessagesTool.DEFINITION, tool);
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

    private ToolOutcome call(TenantId t, Map<String, Object> args) {
        return handler.handle(new ToolCallContext(new AccountContext(t), "rid-" + (++counter)), args);
    }

    private Map<String, Object> args(TenantId t, List<String> hs, String target) {
        Map<String, Object> a = new LinkedHashMap<>();
        a.put("account", t.equals(T1) ? accountHandle1 : accountHandle2);
        a.put("handles", hs);
        a.put("target_folder", target);
        return a;
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
    void definitionMatchesTheContract() {
        var def = MoveMessagesTool.DEFINITION;
        assertThat(def.name()).isEqualTo("move_messages");
        assertThat(def.hints().destructive()).isTrue();
        assertThat(def.hints().idempotent()).isTrue();
        assertThat(def.hints().readOnly()).isFalse();
        assertThat(DescriptionRules.violations(def)).isEmpty();
        assertThat(def.description()).contains("new handles").contains("Gmail").contains("\\All")
                .contains("Trash").contains("replaces");
        assertThat(def.outputSchema()).isEqualTo(BulkOutputSchema.of(BulkResult.Kind.MOVE));
        @SuppressWarnings("unchecked")
        var props = (Map<String, Object>) def.inputSchema().get("properties");
        assertThat(props.keySet()).containsExactly("account", "handles", "target_folder", "target_folder_role");
    }

    @Test
    void movesHandlesFromTwoFoldersToTheTargetWithUidMoveOnly() throws Exception {
        Map<Long, String> inbox = snapshot("INBOX");
        Map<Long, String> src2 = snapshot("Source2");
        List<Long> inboxUids = new ArrayList<>(inbox.keySet());
        long s2uid = src2.keySet().iterator().next();
        String h1 = handle(T1, "INBOX", inboxUids.get(0));
        String h2 = handle(T1, "INBOX", inboxUids.get(2));
        String h3 = handle(T1, "Source2", s2uid);

        Map<String, Object> out = ok(call(T1, args(T1, List.of(h1, h2, h3), "Target")));

        assertThat(out.get("target_folder")).isEqualTo("Target");
        assertThat(results(out)).extracting(r -> r.get("status")).containsOnly("ok");
        Map<Long, String> target = snapshot("Target");
        long tv = validity("Target");
        for (Map<String, Object> r : results(out)) {
            var decoded = handles.decode(T1, account.id(), (String) r.get("new_handle"));
            var ref = ((MessageHandles.Decoded.Ok) decoded).ref();
            assertThat(ref.folder()).isEqualTo("Target");
            assertThat(ref.uidValidity()).isEqualTo(tv);
            assertThat(target).containsKey(ref.uid());
        }
        assertThat(snapshot("INBOX").keySet()).containsExactly(inboxUids.get(1));
        assertThat(snapshot("Source2")).isEmpty();
        String w = wire();
        assertThat(w).contains("UID MOVE");
        assertThat(w).doesNotContain("UID COPY").doesNotContain("UID STORE").doesNotContainPattern("(?m)^\\S+ (UID )?EXPUNGE");
        // old handles are gone, new ones resolve
        long inboxValidity = validity("INBOX");
        leases.withLease(poolKey(), new ToolCallContext(new AccountContext(T1), "r"), lease -> {
            var old = locator().locate(lease, new ToolCallContext(new AccountContext(T1), "r"),
                    new MessageRef("INBOX", inboxValidity, inboxUids.get(0)));
            assertThat(((MessageLocator.Located.Failed) old).error().code()).isEqualTo(ErrorCode.MESSAGE_NOT_FOUND);
            var ref = ((MessageHandles.Decoded.Ok) handles.decode(T1, account.id(),
                    (String) results(out).get(0).get("new_handle"))).ref();
            assertThat(locator().locate(lease, new ToolCallContext(new AccountContext(T1), "r"), ref))
                    .isInstanceOf(MessageLocator.Located.Found.class);
            return null;
        });
    }

    private MessageLocator locator() {
        return new MessageLocator(handles);
    }

    private de.alexanderlindhorst.imapmcp.core.account.PoolKey poolKey() {
        return de.alexanderlindhorst.imapmcp.core.account.PoolKey.of(new AccountContext(T1),
                new de.alexanderlindhorst.imapmcp.core.account.ResolvedAccount(T1, account, accountHandle1));
    }

    @Test
    void retryOfTheSameCallIsAlreadyGoneWithTheSentenceAndNoError() throws Exception {
        long uid = snapshot("INBOX").keySet().iterator().next();
        String h = handle(T1, "INBOX", uid);
        ok(call(T1, args(T1, List.of(h), "Target")));
        ToolOutcome again = call(T1, args(T1, List.of(h), "Target"));
        assertThat(again).isInstanceOf(ToolOutcome.Success.class);
        Map<String, Object> out = ok(again);
        Map<String, Object> item = results(out).get(0);
        assertThat(item.get("status")).isEqualTo("already_gone");
        assertThat(item.get("new_handle")).isNull();
        assertThat(item.get("note")).isNull();
        assertThat(out.get("notes")).isEqualTo(List.of(BulkResult.NOTES.get(2)));
    }

    @Test
    void handlesAlreadyInTheTargetAreAuditedWithTheirStatusInTheSameBatch() throws Exception {
        deliver("Target", "t");
        deliver("INBOX", "i");
        long inTarget = snapshot("Target").keySet().iterator().next();
        long inInbox = snapshot("INBOX").keySet().iterator().next();
        var recorded = new de.alexanderlindhorst.imapmcp.core.audit.RecordingAuditLog();
        ToolCallContext ctx = new ToolCallContext(new AccountContext(T1), "rid-aud");
        ctx.attachAudit(recorded, "move_messages");
        handler.handle(ctx, args(T1, List.of(handle(T1, "Target", inTarget), handle(T1, "INBOX", inInbox)), "Target"));
        assertThat(recorded.begun).hasSize(1);
        assertThat(recorded.begun.get(0)).hasSize(2);
        assertThat(recorded.completions.get(0).items()).extracting(i -> i.status())
                .containsExactlyInAnyOrder("already_in_target", "ok");
    }

    @Test
    void handlesAlreadyInTheTargetSendNoCommandForThatGroup() throws Exception {
        deliver("Target", "t");
        long uid = snapshot("Target").keySet().iterator().next();
        String h = handle(T1, "Target", uid);
        Map<String, Object> out = ok(call(T1, args(T1, List.of(h), "Target")));
        Map<String, Object> item = results(out).get(0);
        assertThat(item.get("status")).isEqualTo("already_in_target");
        assertThat(item.get("new_handle")).isEqualTo(h);
        assertThat(out.get("notes")).isEqualTo(List.of(BulkResult.NOTES.get(0)));
        assertThat(wire()).doesNotContain("UID MOVE").doesNotContain("SELECT");
        assertThat(snapshot("Target")).hasSize(1);
    }

    @Test
    void moveIsRefusedWithoutUidplusBeforeAnySelect() throws Exception {
        long uid = snapshot("INBOX").keySet().iterator().next();
        String h = handle(T1, "INBOX", uid);
        Map<String, Object> a = args(T1, List.of(h), "Target");
        ToolCallContext ctx = new ToolCallContext(new AccountContext(T1), "rid-gate");
        var ok = (WritePreflight.Result.Ok) preflight.check(ctx, accountHandle1, List.of(h), null);
        IMAPStore s = direct();
        try {
            trace.list.clear();
            var lease = LeaseTestSeams.lease(s, ctx);
            LeaseTestSeams.forceCapabilities(lease, true, false);
            ToolOutcome o = tool.onLease(lease, ctx, ok, (String) a.get("account"), List.of(h), "Target", null);
            var f = (ToolOutcome.Failure) o;
            assertThat(f.error().code()).isEqualTo(ErrorCode.UNSUPPORTED);
            assertThat(f.error().details()).containsEntry("capability", "UIDPLUS");
            assertThat(wire()).doesNotContain("SELECT");
            assertThat(ctx.mutationStarted()).isFalse();
        } finally {
            s.close();
        }
        assertThat(snapshot("INBOX")).containsKey(uid);
    }

    @Test
    void missingTargetIsFolderNotFoundAndNothingMoves() throws Exception {
        long uid = snapshot("INBOX").keySet().iterator().next();
        var o = call(T1, args(T1, List.of(handle(T1, "INBOX", uid)), "Nowhere"));
        assertThat(((ToolOutcome.Failure) o).error().code()).isEqualTo(ErrorCode.FOLDER_NOT_FOUND);
        assertThat(snapshot("INBOX")).containsKey(uid);
    }

    @Test
    void movingChangesOnlyTheAddressedUidsNoSeenAndKeepsOtherDeletedMessages() throws Exception {
        // a non-addressed \Deleted message in the source must survive
        var inboxFolder = greenMail.getManagers().getImapHostManager().getFolder(user, "INBOX");
        List<Long> uids = new ArrayList<>(snapshot("INBOX").keySet());
        inboxFolder.setFlags(new Flags(Flags.Flag.DELETED), true, uids.get(2), null, false);
        Map<Long, String> sourceBefore = snapshot("INBOX");
        Map<Long, String> targetBefore = snapshot("Target");

        ok(call(T1, args(T1, List.of(handle(T1, "INBOX", uids.get(0))), "Target")));

        Map<Long, String> sourceAfter = snapshot("INBOX");
        Map<Long, String> targetAfter = snapshot("Target");
        Map<Long, String> expectedSource = new LinkedHashMap<>(sourceBefore);
        expectedSource.remove(uids.get(0));
        assertThat(sourceAfter).isEqualTo(expectedSource);
        assertThat(sourceAfter.get(uids.get(2))).contains("\\Deleted");
        assertThat(targetBefore).isEmpty();
        assertThat(targetAfter).hasSize(1);
        assertThat(targetAfter.values().iterator().next()).doesNotContain("SEEN");
        assertThat(wire()).doesNotContainPattern("(?m)^\\S+ (UID )?STORE").doesNotContain("BODY[").doesNotContainPattern("(?m)^\\S+ EXPUNGE");
    }

    @Test
    void foreignTenantHandleAtAnyPositionIsInvalidHandleWithZeroIoAndNothingMoved() throws Exception {
        List<Long> uids = new ArrayList<>(snapshot("INBOX").keySet());
        String own = handle(T2, "INBOX", uids.get(0));
        String foreign = handle(T1, "INBOX", uids.get(1));
        trace.list.clear();
        long created = pool.createdCount();
        var o = call(T2, args(T2, List.of(own, foreign), "Target"));
        var err = ((ToolOutcome.Failure) o).error();
        assertThat(err.code()).isEqualTo(ErrorCode.INVALID_HANDLE);
        assertThat(err.details()).containsEntry("field", "handles[1]");
        assertThat(pool.createdCount()).isEqualTo(created);
        assertThat(WireTrace.lines(trace)).isEmpty();
        assertThat(snapshot("INBOX").keySet()).containsAll(uids);
        assertThat(snapshot("Target")).isEmpty();
    }
}
