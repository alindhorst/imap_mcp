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
import org.eclipse.angus.mail.iap.Response;
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

/** delete_messages against GreenMail: Trash by SPECIAL-USE only (stubbed here), never an expunge, isolation, no side effects. */
class DeleteMessagesToolTest {

    private static final TenantId T1 = new TenantId("t1");
    private static final TenantId T2 = new TenantId("t2");
    private static final String USER = "deluser";

    private GreenMail greenMail;
    private GreenMailUser user;
    private MailAccount account;
    private ImapSessionFactory sessions;
    private StorePool pool;
    private StoreLeases leases;
    private MessageHandles handles;
    private WritePreflight preflight;
    private DeleteMessagesTool tool;
    private DeleteMessagesTool.TrashResolver trashStub = (lease, rid) -> new FolderResolver.Resolution("Trash", null);
    private MoveStep.Mover mover = UidCommands::uidMove;
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
        rebuild();
        traceLogger = (Logger) LoggerFactory.getLogger("imapmcp.imap.trace");
        traceLogger.setLevel(Level.DEBUG);
        traceLogger.addAppender(trace);
        trace.start();
        createFolder("Source2");
        createFolder("Trash");
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

    private void rebuild() {
        tool = new DeleteMessagesTool(preflight, leases, handles, Clock.systemUTC(), trashStub, mover);
        handler = new ValidatingToolHandler(DeleteMessagesTool.DEFINITION, tool);
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
        var def = DeleteMessagesTool.DEFINITION;
        assertThat(def.name()).isEqualTo("delete_messages");
        assertThat(def.hints().destructive()).isTrue();
        assertThat(def.hints().idempotent()).isTrue();
        assertThat(def.hints().readOnly()).isFalse();
        assertThat(DescriptionRules.violations(def)).isEmpty();
        assertThat(def.description()).contains("not a permanent delete").contains("move_messages")
                .contains("Gmail").contains("removes all labels");
        assertThat(def.outputSchema()).isEqualTo(BulkOutputSchema.of(BulkResult.Kind.DELETE));
        assertThat(def.inputSchema()).containsEntry("required", List.of("account", "handles"))
                .containsEntry("additionalProperties", false);
        @SuppressWarnings("unchecked")
        var props = (Map<String, Object>) def.inputSchema().get("properties");
        assertThat(props.keySet()).containsExactly("account", "handles");
    }

    @Test
    void targetFieldsAreRejectedBySchema() throws Exception {
        long uid = snapshot("INBOX").keySet().iterator().next();
        Map<String, Object> a = args(T1, List.of(handle(T1, "INBOX", uid)));
        a.put("target_folder", "Trash");
        assertThat(call(T1, a)).isInstanceOf(ToolOutcome.Failure.class);
        assertThat(snapshot("INBOX")).containsKey(uid);
    }

    @Test
    void withoutSpecialUseTrashIsFolderNotFoundAndNoMutatingCommandIsSent() throws Exception {
        trashStub = TargetResolver::resolveTrash; // the real resolution: GreenMail reports no \Trash attribute
        rebuild();
        long uid = snapshot("INBOX").keySet().iterator().next();
        trace.list.clear();
        var o = call(T1, args(T1, List.of(handle(T1, "INBOX", uid))));
        var err = ((ToolOutcome.Failure) o).error();
        assertThat(err.code()).isEqualTo(ErrorCode.FOLDER_NOT_FOUND);
        assertThat(err.details()).containsEntry("folder_role", "trash");
        assertThat(err.hint()).contains("move_messages");
        assertThat(wire()).doesNotContain("MOVE").doesNotContain("COPY").doesNotContain("STORE")
                .doesNotContain("EXPUNGE").doesNotContain("SELECT");
        assertThat(snapshot("INBOX")).containsKey(uid);
    }

    @Test
    void inboxHandlesBecomeTrashHandlesAlreadyInTrashAreAlreadyInTargetAndRetryIsAlreadyGone() throws Exception {
        deliver("Trash", "t");
        List<Long> inboxUids = new ArrayList<>(snapshot("INBOX").keySet());
        long trashUid = snapshot("Trash").keySet().iterator().next();
        String h1 = handle(T1, "INBOX", inboxUids.get(0));
        String inTrash = handle(T1, "Trash", trashUid);

        Map<String, Object> out = ok(call(T1, args(T1, List.of(h1, inTrash))));

        assertThat(out.get("target_folder")).isEqualTo("Trash");
        assertThat(results(out).get(0).get("status")).isEqualTo("ok");
        var ref = ((MessageHandles.Decoded.Ok) handles.decode(T1, account.id(),
                (String) results(out).get(0).get("new_handle"))).ref();
        assertThat(ref.folder()).isEqualTo("Trash");
        assertThat(snapshot("Trash")).containsKey(ref.uid()).hasSize(2);
        assertThat(results(out).get(1).get("status")).isEqualTo("already_in_target");
        assertThat(results(out).get(1).get("new_handle")).isEqualTo(inTrash);
        assertThat(out.get("notes")).isEqualTo(List.of(BulkResult.NOTES.get(1)));
        assertThat(snapshot("INBOX").keySet()).doesNotContain(inboxUids.get(0));

        Map<String, Object> again = ok(call(T1, args(T1, List.of(h1))));
        assertThat(results(again).get(0).get("status")).isEqualTo("already_gone");
        assertThat(again.get("notes")).isEqualTo(List.of(BulkResult.NOTES.get(2)));
    }

    @Test
    void sourceEqualToTrashSendsNoCommandAtAll() throws Exception {
        deliver("Trash", "t");
        long trashUid = snapshot("Trash").keySet().iterator().next();
        trace.list.clear();
        Map<String, Object> out = ok(call(T1, args(T1, List.of(handle(T1, "Trash", trashUid)))));
        assertThat(results(out).get(0).get("status")).isEqualTo("already_in_target");
        assertThat(wire()).doesNotContain("MOVE").doesNotContain("COPY").doesNotContain("STORE")
                .doesNotContain("EXPUNGE").doesNotContain("SELECT");
        assertThat(snapshot("Trash")).hasSize(1);
    }

    @Test
    void usesUidMoveAndNeverAPlainOrUidExpunge() throws Exception {
        long uid = snapshot("INBOX").keySet().iterator().next();
        ok(call(T1, args(T1, List.of(handle(T1, "INBOX", uid)))));
        String w = wire();
        assertThat(w).contains("UID MOVE");
        assertThat(w).doesNotContain("UID COPY").doesNotContain("UID STORE").doesNotContainPattern("(?m)^\\S+ (UID )?EXPUNGE");
    }

    @Test
    void overquotaOnTrashFailsTheGroupLeavesTheSourceAndExpungesNothing() throws Exception {
        mover = (folder, uids, target) -> {
            Response r = new Response("A1 NO [OVERQUOTA] full");
            return new UidCommands.Result(r, new Response[] {r});
        };
        rebuild();
        Map<Long, String> before = snapshot("INBOX");
        long uid = before.keySet().iterator().next();
        trace.list.clear();
        Map<String, Object> out = ok(call(T1, args(T1, List.of(handle(T1, "INBOX", uid)))));
        @SuppressWarnings("unchecked")
        var error = (Map<String, Object>) results(out).get(0).get("error");
        assertThat(results(out).get(0).get("status")).isEqualTo("failed");
        assertThat(error.get("code")).isEqualTo("QUOTA_EXCEEDED");
        assertThat(snapshot("INBOX")).isEqualTo(before);
        assertThat(snapshot("Trash")).isEmpty();
        assertThat(wire()).doesNotContain("EXPUNGE");
    }

    @Test
    void onlyTheAddressedUidsMoveAndAForeignDeletedMessageSurvives() throws Exception {
        var inboxFolder = greenMail.getManagers().getImapHostManager().getFolder(user, "INBOX");
        List<Long> uids = new ArrayList<>(snapshot("INBOX").keySet());
        inboxFolder.setFlags(new Flags(Flags.Flag.DELETED), true, uids.get(2), null, false);
        Map<Long, String> sourceBefore = snapshot("INBOX");

        ok(call(T1, args(T1, List.of(handle(T1, "INBOX", uids.get(0))))));

        Map<Long, String> expectedSource = new LinkedHashMap<>(sourceBefore);
        expectedSource.remove(uids.get(0));
        assertThat(snapshot("INBOX")).isEqualTo(expectedSource);
        assertThat(snapshot("INBOX").get(uids.get(2))).contains("\\Deleted");
        assertThat(snapshot("Trash")).hasSize(1);
        assertThat(wire()).doesNotContainPattern("(?m)^\\S+ (UID )?STORE").doesNotContain("BODY[")
                .doesNotContainPattern("(?m)^\\S+ (UID )?EXPUNGE");
    }

    @Test
    void foreignTenantHandleAtAnyPositionIsInvalidHandleWithZeroIoAndNothingMoved() throws Exception {
        List<Long> uids = new ArrayList<>(snapshot("INBOX").keySet());
        String own = handle(T2, "INBOX", uids.get(0));
        String foreign = handle(T1, "INBOX", uids.get(1));
        trace.list.clear();
        long created = pool.createdCount();
        var o = call(T2, args(T2, List.of(own, foreign)));
        var err = ((ToolOutcome.Failure) o).error();
        assertThat(err.code()).isEqualTo(ErrorCode.INVALID_HANDLE);
        assertThat(err.details()).containsEntry("field", "handles[1]");
        assertThat(pool.createdCount()).isEqualTo(created);
        assertThat(trace.list).isEmpty();
        assertThat(snapshot("INBOX").keySet()).containsAll(uids);
        assertThat(snapshot("Trash")).isEmpty();
    }
}
