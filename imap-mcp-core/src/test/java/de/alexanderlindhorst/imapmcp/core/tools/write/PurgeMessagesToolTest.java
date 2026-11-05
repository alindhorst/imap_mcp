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
import de.alexanderlindhorst.imapmcp.core.account.MailAccount.Flavor;
import de.alexanderlindhorst.imapmcp.core.account.MailAccount.Security;
import de.alexanderlindhorst.imapmcp.core.account.MailAccount;
import de.alexanderlindhorst.imapmcp.core.account.PoolKey;
import de.alexanderlindhorst.imapmcp.core.account.ResolvedAccount;
import de.alexanderlindhorst.imapmcp.core.account.Secret;
import de.alexanderlindhorst.imapmcp.core.account.SecretRef;
import de.alexanderlindhorst.imapmcp.core.crypto.KeyRing;
import de.alexanderlindhorst.imapmcp.core.error.ErrorCode;
import de.alexanderlindhorst.imapmcp.core.error.ToolError;
import de.alexanderlindhorst.imapmcp.core.folder.FolderResolver;
import de.alexanderlindhorst.imapmcp.core.handle.MessageHandles;
import de.alexanderlindhorst.imapmcp.core.handle.MessageRef;
import de.alexanderlindhorst.imapmcp.core.imap.CapabilityCache;
import de.alexanderlindhorst.imapmcp.core.imap.ImapSessionFactory;
import de.alexanderlindhorst.imapmcp.core.imap.ImapTimeouts;
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
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;
import java.util.Set;
import org.eclipse.angus.mail.iap.Response;
import org.eclipse.angus.mail.imap.IMAPFolder;
import org.eclipse.angus.mail.imap.IMAPStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

/** purge_messages against GreenMail: Trash only, exact UIDs, check order, refusals, no plain EXPUNGE, isolation. */
class PurgeMessagesToolTest {

    private static final TenantId T1 = new TenantId("t1");
    private static final TenantId T2 = new TenantId("t2");
    private static final String NO_COMMAND = "(?m)^\\S+ (UID )?(MOVE|COPY|STORE|EXPUNGE|SELECT|EXAMINE)\\b";
    private static final String USER = "purgeuser";
    private static final String P1 = BulkResult.PURGE_NOTES.get(0);
    private static final String P2 = BulkResult.PURGE_NOTES.get(1);

    private GreenMail greenMail;
    private GreenMailUser user;
    private MailAccount account;
    private ImapSessionFactory sessions;
    private StorePool pool;
    private StoreLeases leases;
    private MessageHandles handles;
    private WritePreflight preflight;
    private InMemoryAccountRepository repo;
    private PurgeMessagesTool tool;
    private ValidatingToolHandler handler;
    private DeleteMessagesTool.TrashResolver trashStub = (lease, rid) -> new FolderResolver.Resolution("Trash", null);
    private PurgeStep.Wire wire = null;
    private boolean allowPurge = true;
    private boolean readOnly = false;
    private int confirms;
    private PurgeConfirmation confirmation = accepting();
    private Clock clock = Clock.systemUTC();
    private int counter;
    private final ListAppender<ILoggingEvent> trace = new ListAppender<>();
    private final ListAppender<ILoggingEvent> audit = new ListAppender<>();
    private Logger traceLogger;
    private Logger auditLogger;
    private String accountHandle1;
    private String accountHandle2;

    private PurgeConfirmation accepting() {
        return new PurgeConfirmation() {
            @Override
            public boolean available(ToolCallContext ctx) {
                return true;
            }

            @Override
            public Decision confirm(ToolCallContext ctx, Request request) {
                confirms++;
                return new Decision.Confirmed(BulkResult.ELICITED);
            }
        };
    }

    private PurgeConfirmation refusing(ToolError.ElicitationReason reason) {
        return new PurgeConfirmation() {
            @Override
            public boolean available(ToolCallContext ctx) {
                return true;
            }

            @Override
            public Decision confirm(ToolCallContext ctx, Request request) {
                confirms++;
                return new Decision.Refused(reason);
            }
        };
    }

    @BeforeEach
    void setUp() throws Exception {
        greenMail = new GreenMail(ServerSetupTest.IMAPS.dynamicPort());
        greenMail.start();
        user = greenMail.setUser(USER + "@localhost", USER, "pw");
        sessions = new ImapSessionFactory(ImapTimeouts.DEFAULTS, p -> {
            p.put("mail.imaps.ssl.trust", "*");
            p.put("mail.imaps.ssl.checkserveridentity", "false");
            return p;
        }, true);
        pool = new StorePool(sessions, (t, a) -> Optional.of(new Secret("pw".toCharArray())), new CapabilityCache(),
                k -> account, PoolSettings.DEFAULTS);
        leases = new StoreLeases(pool, Duration.ofSeconds(10));
        handles = new MessageHandles(new KeyRing(1, Map.of(1, new byte[32])));
        traceLogger = (Logger) LoggerFactory.getLogger("imapmcp.imap.trace");
        traceLogger.setLevel(Level.DEBUG);
        traceLogger.addAppender(trace);
        trace.start();
        auditLogger = (Logger) LoggerFactory.getLogger("imapmcp.audit");
        auditLogger.setLevel(Level.INFO);
        auditLogger.addAppender(audit);
        audit.start();
        createFolder("Trash");
        createFolder("Junk");
        deliver("INBOX", "a");
        deliver("Trash", "t1");
        deliver("Trash", "t2");
        deliver("Trash", "t3");
        deliver("Junk", "j");
        rebuild();
    }

    @AfterEach
    void tearDown() {
        traceLogger.detachAppender(trace);
        auditLogger.detachAppender(audit);
        pool.close();
        sessions.close();
        greenMail.stop();
    }

    // ---- helpers ----

    private void rebuild() {
        account = new MailAccount(new AccountId("a1"), "Label", null, "127.0.0.1", greenMail.getImaps().getPort(),
                Security.IMPLICIT_TLS, Flavor.GENERIC, USER, new SecretRef("env:X"), readOnly, allowPurge);
        repo = new InMemoryAccountRepository(Map.of(T1, List.of(account), T2, List.of(account)));
        accountHandle1 = InMemoryAccountRepository.handle(T1, account.id());
        accountHandle2 = InMemoryAccountRepository.handle(T2, account.id());
        preflight = new WritePreflight(new AccountArgument(repo), new MessageLocator(handles));
        tool = new PurgeMessagesTool(preflight, leases, clock, confirmation, trashStub, wire);
        handler = new ValidatingToolHandler(PurgeMessagesTool.DEFINITION, tool);
    }

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

    /** uid -> "subject|flags" of a folder, read on a separate connection with EXAMINE. */
    private Map<Long, String> snapshot(String folder) throws Exception {
        IMAPStore s = direct();
        try {
            IMAPFolder f = (IMAPFolder) s.getFolder(folder);
            f.open(Folder.READ_ONLY);
            Map<Long, String> out = new LinkedHashMap<>();
            for (Message m : f.getMessages()) {
                Flags flags = new Flags(m.getFlags());
                flags.remove(Flags.Flag.RECENT);
                out.put(f.getUID(m), m.getSubject() + "|" + flags);
            }
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

    private List<Long> uids(String folder) throws Exception {
        return new ArrayList<>(snapshot(folder).keySet());
    }

    private ToolOutcome call(TenantId t, Map<String, Object> args) {
        return handler.handle(new ToolCallContext(new AccountContext(t), "rid-" + (++counter)), args);
    }

    private Map<String, Object> args(TenantId t, List<String> hs) {
        Map<String, Object> a = new LinkedHashMap<>();
        a.put("account", t.equals(T1) ? accountHandle1 : accountHandle2);
        a.put("handles", hs);
        return a;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> ok(ToolOutcome o) {
        if (o instanceof ToolOutcome.Failure f) {
            throw new AssertionError("unexpected failure " + f.error().toMap());
        }
        return (Map<String, Object>) ((ToolOutcome.Success) o).structured();
    }

    private static ToolError err(ToolOutcome o) {
        return ((ToolOutcome.Failure) o).error();
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> results(Map<String, Object> out) {
        return (List<Map<String, Object>>) out.get("results");
    }

    private String wire() {
        for (int attempt = 0; ; attempt++) {
            try { // a cancel hook of the lease can still be logging on another thread
                return String.join("\n", new ArrayList<>(trace.list).stream().map(ILoggingEvent::getFormattedMessage)
                        .toList());
            } catch (java.util.ConcurrentModificationException e) {
                if (attempt > 20) {
                    throw e;
                }
            }
        }
    }

    private static UidCommands.Result no(String text) {
        Response r = new Response("A1 NO " + text);
        return new UidCommands.Result(r, new Response[] {r});
    }

    private static UidCommands.Result okResult() {
        Response r = new Response("A1 OK done");
        return new UidCommands.Result(r, new Response[] {r});
    }

    // ---- contract ----

    @Test
    void definitionMatchesTheContract() {
        var def = PurgeMessagesTool.DEFINITION;
        assertThat(def.name()).isEqualTo("purge_messages");
        assertThat(def.hints().readOnly()).isFalse();
        assertThat(def.hints().destructive()).isTrue();
        assertThat(def.hints().idempotent()).isTrue();
        assertThat(def.hints().openWorld()).isTrue();
        assertThat(DescriptionRules.violations(def)).isEmpty();
        assertThat(def.description()).startsWith("Delete messages permanently from the Trash folder.")
                .contains("This cannot be undone.").contains(DescriptionRules.WRITE_SENTENCE)
                .contains("delete_messages").contains("confirm").contains("On Gmail this removes the message from all labels.");
        assertThat(def.outputSchema()).isEqualTo(BulkOutputSchema.of(BulkResult.Kind.PURGE));
        assertThat(def.inputSchema()).containsEntry("required", List.of("account", "handles"))
                .containsEntry("additionalProperties", false);
        @SuppressWarnings("unchecked")
        var props = (Map<String, Object>) def.inputSchema().get("properties");
        assertThat(props.keySet()).containsExactly("account", "handles");
    }

    @Test
    void confirmationOrApprovalFieldsAreRejectedBySchema() throws Exception {
        long uid = uids("Trash").get(0);
        for (String extra : List.of("confirm", "allow_for_session", "target_folder", "dry_run")) {
            Map<String, Object> a = args(T1, List.of(handle(T1, "Trash", uid)));
            a.put(extra, true);
            assertThat(call(T1, a)).as(extra).isInstanceOf(ToolOutcome.Failure.class);
        }
        assertThat(snapshot("Trash")).containsKey(uid);
        assertThat(confirms).isZero();
    }

    // ---- check order, zero I/O ----

    @Test
    void readOnlyAccountIsRefusedFirstEvenWithoutAllowPurge() throws Exception {
        readOnly = true;
        allowPurge = false;
        rebuild();
        trace.list.clear();
        var e = err(call(T1, args(T1, List.of(handle(T1, "Trash", uids("Trash").get(0))))));
        assertThat(e.code()).isEqualTo(ErrorCode.PERMISSION_DENIED);
        assertThat(e.details()).containsEntry("reason", "account_read_only");
    }

    @Test
    void accountWithoutAllowPurgeIsPurgeDisabledBeforeAnyIo() throws Exception {
        allowPurge = false;
        rebuild();
        String h = handle(T1, "Trash", uids("Trash").get(0));
        trace.list.clear();
        long created = pool.createdCount();
        var e = err(call(T1, args(T1, List.of(h))));
        assertThat(e.code()).isEqualTo(ErrorCode.PERMISSION_DENIED);
        assertThat(e.details()).containsEntry("reason", "purge_disabled");
        assertThat(e.hint()).contains("accounts[].allowPurge");
        assertThat(pool.createdCount()).isEqualTo(created);
        assertThat(trace.list).isEmpty();
        assertThat(confirms).isZero();
    }

    @Test
    void purgeDisabledComesBeforeInvalidHandles() {
        allowPurge = false;
        rebuild();
        var e = err(call(T1, args(T1, List.of("not-a-handle"))));
        assertThat(e.details()).containsEntry("reason", "purge_disabled");
    }

    @Test
    void sessionThatCannotConfirmIsConfirmationUnavailableBeforeAnyIoAndBeforeHandles() throws Exception {
        confirmation = PurgeConfirmation.UNAVAILABLE;
        rebuild();
        trace.list.clear();
        long created = pool.createdCount();
        var e = err(call(T1, args(T1, List.of("not-a-handle"))));
        assertThat(e.code()).isEqualTo(ErrorCode.PERMISSION_DENIED);
        assertThat(e.details()).containsEntry("reason", "confirmation_unavailable");
        assertThat(e.hint()).contains("MCP elicitation").contains("nothing was deleted");
        assertThat(pool.createdCount()).isEqualTo(created);
        assertThat(trace.list).isEmpty();
    }

    @Test
    void defaultWiringCannotConfirmSoNothingIsEverDeleted() throws Exception {
        tool = new PurgeMessagesTool(preflight, leases, clock, PurgeConfirmation.UNAVAILABLE);
        handler = new ValidatingToolHandler(PurgeMessagesTool.DEFINITION, tool);
        Map<Long, String> before = snapshot("Trash");
        var e = err(call(T1, args(T1, List.of(handle(T1, "Trash", uids("Trash").get(0))))));
        assertThat(e.details()).containsEntry("reason", "confirmation_unavailable");
        assertThat(snapshot("Trash")).isEqualTo(before);
    }

    @Test
    void foreignTenantHandleAtAnyPositionIsInvalidHandleWithZeroIoAndNothingDeleted() throws Exception {
        List<Long> t = uids("Trash");
        String own = handle(T2, "Trash", t.get(0));
        String foreign = handle(T1, "Trash", t.get(1));
        trace.list.clear();
        long created = pool.createdCount();
        var e = err(call(T2, args(T2, List.of(own, foreign))));
        assertThat(e.code()).isEqualTo(ErrorCode.INVALID_HANDLE);
        assertThat(e.details()).containsEntry("field", "handles[1]");
        assertThat(pool.createdCount()).isEqualTo(created);
        assertThat(trace.list).isEmpty();
        assertThat(uids("Trash")).containsAll(t);
        assertThat(confirms).isZero();
    }

    @Test
    void missingUidplusIsUnsupportedBeforeAnySelect() throws Exception {
        List<Long> t = uids("Trash");
        ToolCallContext ctx = new ToolCallContext(new AccountContext(T1), "rid-x");
        var ok = (WritePreflight.Result.Ok) preflight.check(ctx, accountHandle1, List.of(handle(T1, "Trash", t.get(0))),
                null);
        var key = PoolKey.of(new AccountContext(T1), new ResolvedAccount(T1, account, accountHandle1));
        IMAPStore s = (IMAPStore) sessions.sessionFor(key, account).getStore("imaps");
        s.connect("127.0.0.1", greenMail.getImaps().getPort(), USER, "pw");
        try {
            trace.list.clear();
            var lease = LeaseTestSeams.lease(s, ctx);
            LeaseTestSeams.forceCapabilities(lease, true, false);
            var out = tool.onLease(lease, ctx, ok, accountHandle1, List.of(handle(T1, "Trash", t.get(0))));
            var e = err(out);
            assertThat(e.code()).isEqualTo(ErrorCode.UNSUPPORTED);
            assertThat(e.details()).containsEntry("capability", "UIDPLUS");
            assertThat(wire()).doesNotContainPattern(NO_COMMAND);
            assertThat(confirms).isZero();
        } finally {
            s.close();
        }
        assertThat(uids("Trash")).containsAll(t);
    }

    @Test
    void handleOutsideTrashIsPurgeScopeNamingTheFirstPositionBeforeSelectAndBeforeAsking() throws Exception {
        List<Long> t = uids("Trash");
        String inTrash = handle(T1, "Trash", t.get(0));
        String inbox = handle(T1, "INBOX", uids("INBOX").get(0));
        String junk = handle(T1, "Junk", uids("Junk").get(0));
        trace.list.clear();
        var e = err(call(T1, args(T1, List.of(inTrash, junk, inbox))));
        assertThat(e.code()).isEqualTo(ErrorCode.PERMISSION_DENIED);
        assertThat(e.details()).containsEntry("reason", "purge_scope").containsEntry("field", "handles[1]");
        assertThat(e.hint()).contains("delete_messages");
        assertThat(wire()).doesNotContainPattern(NO_COMMAND);
        assertThat(confirms).isZero();
        assertThat(uids("Trash")).containsAll(t);
        assertThat(uids("INBOX")).hasSize(1);
        assertThat(uids("Junk")).hasSize(1);
    }

    @Test
    void accountWithoutTrashRoleFailsEveryHandleAsPurgeScopeAtPositionZero() throws Exception {
        trashStub = TargetResolver::resolveTrash; // the real resolution: GreenMail reports no \Trash attribute
        rebuild();
        trace.list.clear();
        var e = err(call(T1, args(T1, List.of(handle(T1, "Trash", uids("Trash").get(0))))));
        assertThat(e.details()).containsEntry("reason", "purge_scope").containsEntry("field", "handles[0]");
        assertThat(wire()).doesNotContainPattern(NO_COMMAND);
        assertThat(uids("Trash")).hasSize(3);
    }

    @Test
    void refusedConfirmationIsNotConfirmedAndNothingIsSentOrChanged() throws Exception {
        List<Long> t = uids("Trash");
        for (ToolError.ElicitationReason reason : ToolError.ElicitationReason.values()) {
            confirmation = refusing(reason);
            rebuild();
            trace.list.clear();
            var e = err(call(T1, args(T1, List.of(handle(T1, "Trash", t.get(0))))));
            assertThat(e.code()).isEqualTo(ErrorCode.PERMISSION_DENIED);
            assertThat(e.details()).containsEntry("reason", "not_confirmed")
                    .containsEntry("elicitation", reason.name().toLowerCase());
            assertThat(wire()).doesNotContainPattern("(?m)^\\S+ (UID )?(STORE|EXPUNGE)\\b");
            assertThat(uids("Trash")).containsAll(t);
        }
    }

    // ---- the purge ----

    @Test
    void purgesExactlyTheAddressedUidsAndAForeignDeletedMessageSurvives() throws Exception {
        List<Long> t = uids("Trash");
        var trashFolder = greenMail.getManagers().getImapHostManager().getFolder(user, "Trash");
        trashFolder.setFlags(new Flags(Flags.Flag.DELETED), true, t.get(2), null, false);
        String h0 = handle(T1, "Trash", t.get(0));
        String h1 = handle(T1, "Trash", t.get(1));
        trace.list.clear();

        Map<String, Object> out = ok(call(T1, args(T1, List.of(h0, h1))));

        assertThat(out.keySet()).containsExactly("account", "confirmation", "summary", "results", "notes");
        assertThat(out).containsEntry("confirmation", "elicited").containsEntry("notes", List.of(P1));
        assertThat(results(out)).extracting(r -> r.get("status")).containsExactly("ok", "ok");
        assertThat(results(out).get(0).keySet()).containsExactly("handle", "status", "reason", "error");
        Map<Long, String> after = snapshot("Trash");
        assertThat(after.keySet()).containsExactly(t.get(2));
        assertThat(after.get(t.get(2))).contains("\\Deleted");
        assertThat(snapshot("INBOX")).hasSize(1);
        assertThat(snapshot("Junk")).hasSize(1);
        String w = wire();
        int store = w.indexOf("UID STORE");
        int expunge = w.indexOf("UID EXPUNGE");
        assertThat(store).isNotNegative();
        assertThat(expunge).isGreaterThan(store);
        assertThat(w).contains("+FLAGS.SILENT (\\Deleted)").doesNotContainPattern("(?m)^\\S+ EXPUNGE")
                .doesNotContain("UID MOVE").doesNotContain("UID COPY");
        String expungeLine = w.lines().filter(l -> l.contains("UID EXPUNGE")).findFirst().orElseThrow();
        assertThat(expungeLine).doesNotContain(String.valueOf(t.get(2)) + " ").doesNotEndWith("," + t.get(2));
        assertThat(w.lines().filter(l -> l.contains("UID FETCH")).count()).isGreaterThanOrEqualTo(2); // pre- and post-check
        assertThat(confirms).isEqualTo(1);
    }

    @Test
    void aRepeatIsAlreadyGoneAndSendsNoMutatingCommand() throws Exception {
        List<Long> t = uids("Trash");
        String h = handle(T1, "Trash", t.get(0));
        ok(call(T1, args(T1, List.of(h))));
        trace.list.clear();
        Map<String, Object> again = ok(call(T1, args(T1, List.of(h))));
        assertThat(results(again).get(0)).containsEntry("status", "already_gone");
        assertThat(again.get("notes")).isEqualTo(List.of(P2));
        assertThat(wire()).doesNotContainPattern("(?m)^\\S+ (UID )?(STORE|EXPUNGE)\\b");
    }

    @Test
    void staleValidityIsStaleReferenceAndNothingIsChanged() throws Exception {
        List<Long> t = uids("Trash");
        String stale = handles.encode(T1, account.id(), new MessageRef("Trash", validity("Trash") + 1, t.get(0)));
        trace.list.clear();
        Map<String, Object> out = ok(call(T1, args(T1, List.of(stale))));
        assertThat(results(out).get(0)).containsEntry("status", "stale_reference").containsEntry("reason",
                "uidvalidity_changed");
        assertThat(wire()).doesNotContainPattern("(?m)^\\S+ (UID )?(STORE|EXPUNGE)\\b");
        assertThat(uids("Trash")).hasSize(3);
    }

    // ---- refused commands (stubbed wire) ----

    @Test
    void refusedStoreFailsTheGroupAndSendsNoUidExpunge() throws Exception {
        List<String> sent = new ArrayList<>();
        wire = new PurgeStep.Wire() {
            @Override
            public UidCommands.Result store(IMAPFolder f, Collection<Long> uids) {
                sent.add("store");
                return no("[NOPERM] no");
            }

            @Override
            public UidCommands.Result expunge(IMAPFolder f, Collection<Long> uids) {
                sent.add("expunge");
                return okResult();
            }

            @Override
            public Set<Long> present(IMAPFolder f, Collection<Long> uids) {
                sent.add("present");
                return Set.copyOf(uids);
            }
        };
        rebuild();
        Map<String, Object> out = ok(call(T1, args(T1, List.of(handle(T1, "Trash", uids("Trash").get(0))))));
        assertThat(sent).containsExactly("store");
        assertThat(results(out).get(0)).containsEntry("status", "failed").containsEntry("reason", null);
        assertThat(out.get("notes")).isEqualTo(List.of(BulkResult.PURGE_NOTES.get(4)));
        assertThat(uids("Trash")).hasSize(3);
    }

    @Test
    void refusedExpungeThenPostCheckHitIsStillPresentWithTheMappedError() throws Exception {
        wire = new PurgeStep.Wire() {
            @Override
            public UidCommands.Result store(IMAPFolder f, Collection<Long> uids) {
                return okResult();
            }

            @Override
            public UidCommands.Result expunge(IMAPFolder f, Collection<Long> uids) {
                return no("[OVERQUOTA] x");
            }

            @Override
            public Set<Long> present(IMAPFolder f, Collection<Long> uids) {
                return Set.copyOf(uids);
            }
        };
        rebuild();
        Map<String, Object> out = ok(call(T1, args(T1, List.of(handle(T1, "Trash", uids("Trash").get(0))))));
        assertThat(results(out).get(0)).containsEntry("status", "failed").containsEntry("reason", "still_present");
        @SuppressWarnings("unchecked")
        var error = (Map<String, Object>) results(out).get(0).get("error");
        assertThat(error.get("code")).isEqualTo("QUOTA_EXCEEDED");
        assertThat(out.get("notes")).isEqualTo(List.of(BulkResult.PURGE_NOTES.get(3), BulkResult.PURGE_NOTES.get(4)));
    }

    @Test
    void successfulExpungeButPostCheckHitIsStillPresentWithUpstreamError() throws Exception {
        wire = new PurgeStep.Wire() {
            @Override
            public UidCommands.Result store(IMAPFolder f, Collection<Long> uids) {
                return okResult();
            }

            @Override
            public UidCommands.Result expunge(IMAPFolder f, Collection<Long> uids) {
                return okResult();
            }

            @Override
            public Set<Long> present(IMAPFolder f, Collection<Long> uids) {
                return Set.copyOf(uids);
            }
        };
        rebuild();
        Map<String, Object> out = ok(call(T1, args(T1, List.of(handle(T1, "Trash", uids("Trash").get(0))))));
        @SuppressWarnings("unchecked")
        var error = (Map<String, Object>) results(out).get(0).get("error");
        assertThat(results(out).get(0)).containsEntry("reason", "still_present");
        assertThat(error.get("code")).isEqualTo("UPSTREAM_ERROR");
    }

    @Test
    void refusedExpungeButAbsentInThePostCheckIsOk() throws Exception {
        wire = new PurgeStep.Wire() {
            @Override
            public UidCommands.Result store(IMAPFolder f, Collection<Long> uids) {
                return okResult();
            }

            @Override
            public UidCommands.Result expunge(IMAPFolder f, Collection<Long> uids) {
                return no("[CANNOT] x");
            }

            @Override
            public Set<Long> present(IMAPFolder f, Collection<Long> uids) {
                return Set.of(); // Gmail auto-expunge: the STORE alone removed it
            }
        };
        rebuild();
        Map<String, Object> out = ok(call(T1, args(T1, List.of(handle(T1, "Trash", uids("Trash").get(0))))));
        assertThat(results(out).get(0)).containsEntry("status", "ok");
    }

    // ---- tryBeginMutation and the budget ----

    @Test
    void cancelDuringConfirmationDeletesNothing() throws Exception {
        confirmation = new PurgeConfirmation() {
            @Override
            public boolean available(ToolCallContext ctx) {
                return true;
            }

            @Override
            public Decision confirm(ToolCallContext ctx, Request request) {
                ctx.cancel(); // the deadline or a cancel is decided while the user answers
                return new Decision.Confirmed(BulkResult.ELICITED);
            }
        };
        rebuild();
        String h = handle(T1, "Trash", uids("Trash").get(0));
        ToolCallContext ctx = new ToolCallContext(new AccountContext(T1), "rid-c");
        trace.list.clear();
        // the cancel also runs the lease's hooks, so the result is an error or a not_attempted item; the executor
        // discards either and reports the plain TIMEOUT. What matters: no mutation mark, no mutating command.
        handler.handle(ctx, args(T1, List.of(h)));
        assertThat(ctx.mutationStarted()).isFalse();
        assertThat(wire()).doesNotContainPattern("(?m)^\\S+ (UID )?(STORE|EXPUNGE)\\b");
        assertThat(uids("Trash")).hasSize(3);
    }

    @Test
    void lessThanTheWriteBudgetLeftIsNotAttemptedWithoutAMutatingCommand() throws Exception {
        String h = handle(T1, "Trash", uids("Trash").get(0));
        ToolCallContext ctx = new ToolCallContext(new AccountContext(T1), "rid-b", Instant.now().plusSeconds(5));
        trace.list.clear();
        Map<String, Object> out = ok(handler.handle(ctx, args(T1, List.of(h))));
        assertThat(results(out).get(0)).containsEntry("status", "not_attempted");
        assertThat(wire()).doesNotContainPattern("(?m)^\\S+ (UID )?(STORE|EXPUNGE)\\b");
        assertThat(ctx.mutationStarted()).isFalse();
    }

    @Test
    void mutationIsMarkedOnlyOnceTheCallIsConfirmedAndGoingToWrite() throws Exception {
        String h = handle(T1, "Trash", uids("Trash").get(0));
        ToolCallContext ctx = new ToolCallContext(new AccountContext(T1), "rid-m");
        ok(handler.handle(ctx, args(T1, List.of(h))));
        assertThat(ctx.mutationStarted()).isTrue();
        ToolCallContext refused = new ToolCallContext(new AccountContext(T1), "rid-n");
        confirmation = refusing(ToolError.ElicitationReason.DECLINED);
        rebuild();
        handler.handle(refused, args(T1, List.of(handle(T1, "Trash", uids("Trash").get(0)))));
        assertThat(refused.mutationStarted()).isFalse();
    }

    // ---- audit ----

    @Test
    void oneAuditLineWithConfirmationPathAndCountsAndNoNamesOrHandles() throws Exception {
        auditLogger.setLevel(Level.INFO);
        List<Long> t = uids("Trash");
        String h = handle(T1, "Trash", t.get(0));
        ok(call(T1, args(T1, List.of(h))));
        assertThat(audit.list).hasSize(1);
        ILoggingEvent e = audit.list.get(0);
        assertThat(e.getLevel()).isEqualTo(Level.INFO);
        assertThat(e.getLoggerName()).isEqualTo("imapmcp.audit");
        String m = e.getFormattedMessage();
        assertThat(m).contains("tool=purge_messages").contains("path=expunge").contains("confirmation=elicited")
                .contains("outcome=complete").contains("ok=1").contains("groups=1");
        assertThat(m).doesNotContain(h).doesNotContain("Trash").doesNotContain("Label").doesNotContain("t1");
    }

    @Test
    void refusalsAreAuditedWithTheErrorCodeAndTheElicitationValue() throws Exception {
        confirmation = refusing(ToolError.ElicitationReason.DECLINED);
        rebuild();
        call(T1, args(T1, List.of(handle(T1, "Trash", uids("Trash").get(0)))));
        assertThat(audit.list).hasSize(1);
        assertThat(audit.list.get(0).getFormattedMessage()).contains("error_code=PERMISSION_DENIED")
                .contains("confirmation=declined");
    }
}
