package de.alexanderlindhorst.imapmcp.core.tools.write;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

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
import de.alexanderlindhorst.imapmcp.core.error.ErrorCode;
import de.alexanderlindhorst.imapmcp.core.error.ToolError;
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
import de.alexanderlindhorst.imapmcp.core.mime.BodyWindowReader;
import de.alexanderlindhorst.imapmcp.core.tenancy.AccountContext;
import de.alexanderlindhorst.imapmcp.core.tenancy.AccountId;
import de.alexanderlindhorst.imapmcp.core.tenancy.TenantId;
import de.alexanderlindhorst.imapmcp.core.tool.DescriptionRules;
import de.alexanderlindhorst.imapmcp.core.tool.ToolCallContext;
import de.alexanderlindhorst.imapmcp.core.tool.ToolExecutor;
import de.alexanderlindhorst.imapmcp.core.tool.ToolOutcome;
import de.alexanderlindhorst.imapmcp.core.tool.ValidatingToolHandler;
import de.alexanderlindhorst.imapmcp.core.tools.AccountArgument;
import de.alexanderlindhorst.imapmcp.core.tools.GetMessageTool;
import de.alexanderlindhorst.imapmcp.core.tools.MessageLocator;
import jakarta.mail.Flags;
import jakarta.mail.Folder;
import jakarta.mail.Message;
import jakarta.mail.MessagingException;
import jakarta.mail.Session;
import jakarta.mail.Store;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeMessage;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import org.eclipse.angus.mail.iap.Response;
import org.eclipse.angus.mail.imap.IMAPFolder;
import org.eclipse.angus.mail.imap.IMAPStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

class CopyMessagesToolTest {

    static final TenantId A = new TenantId("tenant-a");
    static final TenantId B = new TenantId("tenant-b");
    static final AccountId ACCT = new AccountId("acct");
    static final KeyRing KEYS = new KeyRing(1, Map.of(1, new byte[32]));
    static final String DST = "Dst";

    @RegisterExtension
    static final GreenMailExtension GREEN_MAIL = new GreenMailExtension(ServerSetupTest.IMAPS.dynamicPort());

    final MessageHandles handles = new MessageHandles(KEYS);
    ImapSessionFactory sessions;
    StorePool pool;
    InMemoryAccountRepository repo;
    StoreLeases leases;
    WritePreflight preflight;
    String acctHandle;

    @BeforeEach
    void setUp() throws Exception {
        GREEN_MAIL.setUser("user@localhost", "user", "secret");
        MailAccount account = new MailAccount(ACCT, "acct", null, "127.0.0.1", GREEN_MAIL.getImaps().getPort(),
                Security.IMPLICIT_TLS, Flavor.GENERIC, "user", new SecretRef("env:x"));
        sessions = new ImapSessionFactory(ImapTimeouts.DEFAULTS, p -> {
            p.put("mail.imaps.ssl.trust", "*");
            p.put("mail.imaps.ssl.checkserveridentity", "false");
            return p;
        }, true);
        repo = new InMemoryAccountRepository(Map.of(A, List.of(account), B, List.of(account)));
        pool = new StorePool(sessions, (t, a) -> Optional.of(new Secret("secret".toCharArray())),
                new CapabilityCache(), k -> account, PoolSettings.DEFAULTS);
        leases = new StoreLeases(pool, Duration.ofSeconds(10));
        preflight = new WritePreflight(new AccountArgument(repo), new MessageLocator(handles));
        acctHandle = InMemoryAccountRepository.handle(A, ACCT);
        try (Store s = client()) {
            Folder dst = s.getFolder(DST);
            dst.create(Folder.HOLDS_MESSAGES);
        }
    }

    @AfterEach
    void tearDown() {
        pool.close();
        sessions.close();
    }

    // fixtures

    static final Session MAIL = Session.getInstance(new java.util.Properties());

    Store client() throws Exception {
        java.util.Properties props = new java.util.Properties();
        props.put("mail.imaps.ssl.trust", "*");
        props.put("mail.imaps.ssl.checkserveridentity", "false");
        Store s = Session.getInstance(props).getStore("imaps");
        s.connect("127.0.0.1", GREEN_MAIL.getImaps().getPort(), "user", "secret");
        return s;
    }

    String deliver(String folder, String subject, boolean seen, TenantId tenant) throws Exception {
        MimeMessage msg = new MimeMessage(MAIL);
        msg.setFrom(new InternetAddress("alice@example.org"));
        msg.setRecipients(Message.RecipientType.TO, "user@localhost");
        msg.setSubject(subject);
        msg.setText("body of " + subject);
        msg.saveChanges();
        try (Store s = client()) {
            IMAPFolder f = (IMAPFolder) s.getFolder(folder);
            if (!f.exists()) {
                f.create(Folder.HOLDS_MESSAGES);
            }
            f.open(Folder.READ_WRITE);
            f.appendMessages(new Message[] {msg});
            Message last = f.getMessage(f.getMessageCount());
            if (seen) {
                last.setFlag(Flags.Flag.SEEN, true);
            }
            MessageRef ref = new MessageRef(folder, f.getUIDValidity(), f.getUID(last));
            f.close(false);
            return handles.encode(tenant, ACCT, ref);
        }
    }

    String deliver(String folder, String subject) throws Exception {
        return deliver(folder, subject, false, A);
    }

    /** "uid:seen" for every message of a folder, in UID order; EXAMINE only. */
    List<String> snapshot(String folder) throws Exception {
        try (Store s = client()) {
            IMAPFolder f = (IMAPFolder) s.getFolder(folder);
            f.open(Folder.READ_ONLY);
            List<String> out = new ArrayList<>();
            for (Message m : f.getMessages()) {
                out.add(f.getUID(m) + ":" + m.isSet(Flags.Flag.SEEN));
            }
            f.close(false);
            return out;
        }
    }

    CopyMessagesTool tool() {
        return new CopyMessagesTool(preflight, (k, c, w) -> leases.withLease(k, c, w), handles,
                new FolderGroupRunner(Clock.systemUTC()), UidCommands::uidCopy);
    }

    CopyMessagesTool tool(CopyMessagesTool.CopyCommand copy) {
        return new CopyMessagesTool(preflight, (k, c, w) -> leases.withLease(k, c, w), handles,
                new FolderGroupRunner(Clock.systemUTC()), copy);
    }

    /** A tool whose lease must never be taken: proves zero I/O. */
    CopyMessagesTool noLeaseTool() {
        return new CopyMessagesTool(preflight, (k, c, w) -> {
            throw new AssertionError("no lease may be taken");
        }, handles, new FolderGroupRunner(Clock.systemUTC()), UidCommands::uidCopy);
    }

    ToolCallContext ctx(TenantId tenant) {
        return new ToolCallContext(new AccountContext(tenant), "rid");
    }

    static Map<String, Object> args(String account, List<String> handles, String folder, String role) {
        Map<String, Object> m = new HashMap<>();
        m.put("account", account);
        m.put("handles", handles);
        if (folder != null) {
            m.put("target_folder", folder);
        }
        if (role != null) {
            m.put("target_folder_role", role);
        }
        return m;
    }

    static ToolError error(ToolOutcome o) {
        assertThat(o).isInstanceOf(ToolOutcome.Failure.class);
        return ((ToolOutcome.Failure) o).error();
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> result(ToolOutcome o) {
        assertThat(o).isInstanceOf(ToolOutcome.Success.class);
        return (Map<String, Object>) ((ToolOutcome.Success) o).structured();
    }

    @SuppressWarnings("unchecked")
    static List<Map<String, Object>> items(ToolOutcome o) {
        return (List<Map<String, Object>>) result(o).get("results");
    }

    MessageRef decode(String handle) {
        return ((MessageHandles.Decoded.Ok) handles.decode(A, ACCT, handle)).ref();
    }

    // definition

    @Test
    void definitionIsAdditiveNonIdempotentAndFollowsTheDescriptionRules() {
        var def = CopyMessagesTool.DEFINITION;
        assertThat(def.name()).isEqualTo("copy_messages");
        assertThat(def.hints().readOnly()).isFalse();
        assertThat(def.hints().destructive()).isFalse();
        assertThat(def.hints().idempotent()).isFalse();
        assertThat(def.hints().openWorld()).isTrue();
        assertThat(DescriptionRules.violations(def)).isEmpty();
        assertThat(def.description()).contains("duplicate").contains("Undo").contains("Gmail").contains("label");
        assertThat(def.outputSchema()).isEqualTo(BulkOutputSchema.of(BulkResult.Kind.COPY));
    }

    @Test
    @SuppressWarnings("unchecked")
    void inputSchemaHasOneToHundredUniqueHandlesAndBothTargetFields() {
        Map<String, Object> props = (Map<String, Object>) CopyMessagesTool.DEFINITION.inputSchema().get("properties");
        assertThat(props.keySet()).containsExactly("account", "handles", "target_folder", "target_folder_role");
        Map<String, Object> h = (Map<String, Object>) props.get("handles");
        assertThat(h).containsEntry("minItems", 1).containsEntry("maxItems", 100).containsEntry("uniqueItems", true);
        Map<String, Object> tf = (Map<String, Object>) props.get("target_folder");
        assertThat(tf).containsEntry("minLength", 1).containsEntry("maxLength", 1000);
        assertThat((List<Object>) ((Map<String, Object>) props.get("target_folder_role")).get("enum"))
                .contains("trash", "inbox");
        assertThat((List<Object>) CopyMessagesTool.DEFINITION.inputSchema().get("required"))
                .containsExactly("account", "handles");
    }

    @Test
    void schemaValidationRejectsEmptyTooLongAndDuplicateHandleLists() {
        var validating = new ValidatingToolHandler(CopyMessagesTool.DEFINITION, (c, a) -> {
            throw new AssertionError("must not run");
        });
        List<String> hundredOne = new ArrayList<>();
        for (int i = 0; i < 101; i++) {
            hundredOne.add("h" + i);
        }
        for (List<String> bad : List.of(List.<String>of(), hundredOne, List.of("x", "x"))) {
            ToolOutcome o = validating.handle(ctx(A), args(acctHandle, bad, DST, null));
            assertThat(error(o).code()).isEqualTo(ErrorCode.INVALID_ARGUMENT);
        }
    }

    // argument shape (step 1)

    @Test
    void neitherAndBothTargetsAreRefusedBeforeAnyIo() throws Exception {
        String h = deliver("INBOX", "s");
        ToolError neither = error(noLeaseTool().handle(ctx(A), args(acctHandle, List.of(h), null, null)));
        assertThat(neither.code()).isEqualTo(ErrorCode.INVALID_ARGUMENT);
        assertThat(neither.details()).containsEntry("field", "target_folder").containsEntry("reason", "required");
        ToolError both = error(noLeaseTool().handle(ctx(A), args(acctHandle, List.of(h), DST, "trash")));
        assertThat(both.details()).containsEntry("field", "target_folder_role")
                .containsEntry("reason", "mutually_exclusive");
    }

    // happy path

    @Test
    void copiesFromTwoFoldersToAVerbatimTargetWithResolvingNewHandlesAndUnchangedSource() throws Exception {
        String h1 = deliver("INBOX", "first", true, A);
        String h2 = deliver("INBOX", "second", false, A);
        String h3 = deliver("Src2", "third", false, A);
        List<String> inboxBefore = snapshot("INBOX");
        List<String> srcBefore = snapshot("Src2");
        assertThat(snapshot(DST)).isEmpty();

        ToolOutcome o = tool().handle(ctx(A), args(acctHandle, List.of(h1, h3, h2), DST, null));

        Map<String, Object> out = result(o);
        assertThat(out.get("account")).isEqualTo(acctHandle);
        assertThat(out.get("target_folder")).isEqualTo(DST);
        List<Map<String, Object>> items = items(o);
        assertThat(items).extracting(i -> i.get("handle")).containsExactly(h1, h3, h2);
        assertThat(items).extracting(i -> i.get("status")).containsOnly("ok");
        assertThat(items).extracting(i -> i.get("note")).containsOnlyNulls();
        assertThat(out.get("notes")).isEqualTo(List.of());
        GetMessageTool get = new GetMessageTool(new AccountArgument(repo), new MessageLocator(handles), leases,
                new BodyWindowReader(), 100000);
        List<String> subjects = new ArrayList<>();
        for (Map<String, Object> i : items) {
            String nh = (String) i.get("new_handle");
            assertThat(nh).isNotNull();
            assertThat(decode(nh).folder()).isEqualTo(DST);
            Map<String, Object> msg = result(get.handle(ctx(A), Map.of("account", acctHandle, "message", nh)));
            @SuppressWarnings("unchecked")
            Map<String, Object> untrusted = (Map<String, Object>) msg.get("untrusted");
            subjects.add((String) untrusted.get("subject"));
        }
        assertThat(subjects).containsExactly("first", "third", "second");
        assertThat(snapshot("INBOX")).isEqualTo(inboxBefore);
        assertThat(snapshot("Src2")).isEqualTo(srcBefore);
    }

    @Test
    void sourceIsUntouchedAndOnlyNewUidsAppearInTheTargetWithTheirSeenFlagUnchanged() throws Exception {
        String seen = deliver("INBOX", "seen one", true, A);
        String unseen = deliver("INBOX", "unseen one", false, A);
        List<String> before = snapshot("INBOX");
        tool().handle(ctx(A), args(acctHandle, List.of(seen, unseen), DST, null));
        assertThat(snapshot("INBOX")).isEqualTo(before);
        // two new messages; the \Seen flag is the one of the source message, nothing was marked read by the copy
        assertThat(snapshot(DST)).hasSize(2).extracting(s -> s.substring(s.indexOf(':') + 1))
                .containsExactly("true", "false");
    }

    @Test
    void newHandlesArePairedByTheCopyUidResponseOrderNotTheRequestOrder() throws Exception {
        String h1 = deliver("INBOX", "a");
        String h2 = deliver("INBOX", "b");
        String h3 = deliver("INBOX", "c");
        long u1 = decode(h1).uid();
        long u2 = decode(h2).uid();
        long u3 = decode(h3).uid();
        List<Collection<Long>> requested = new ArrayList<>();
        CopyMessagesTool.CopyCommand scripted = (folder, uids, target) -> {
            requested.add(List.copyOf(uids));
            String line = "A9 OK [COPYUID 77 " + u3 + "," + u1 + "," + u2 + " 33,31,32] Copy completed";
            Response tagged = new Response(line);
            return new UidCommands.Result(tagged, new Response[] {tagged});
        };
        ToolOutcome o = tool(scripted).handle(ctx(A), args(acctHandle, List.of(h1, h2, h3), DST, null));
        List<Map<String, Object>> items = items(o);
        assertThat(items).extracting(i -> i.get("status")).containsOnly("ok");
        assertThat(requested).hasSize(1);
        assertThat(requested.get(0)).containsExactlyInAnyOrder(u1, u2, u3);
        assertThat(decode((String) items.get(0).get("new_handle"))).isEqualTo(new MessageRef(DST, 77, 31));
        assertThat(decode((String) items.get(1).get("new_handle"))).isEqualTo(new MessageRef(DST, 77, 32));
        assertThat(decode((String) items.get(2).get("new_handle"))).isEqualTo(new MessageRef(DST, 77, 33));
    }

    @Test
    void missingCopyUidIsOkWithNewUidUnknownAndTheMatchingNote() throws Exception {
        String h = deliver("INBOX", "a");
        CopyMessagesTool.CopyCommand noCode = (folder, uids, target) -> {
            Response tagged = new Response("A9 OK Copy completed");
            return new UidCommands.Result(tagged, new Response[] {tagged});
        };
        ToolOutcome o = tool(noCode).handle(ctx(A), args(acctHandle, List.of(h), DST, null));
        Map<String, Object> item = items(o).get(0);
        assertThat(item.get("status")).isEqualTo("ok");
        assertThat(item.get("new_handle")).isNull();
        assertThat(item.get("note")).isEqualTo("new_uid_unknown");
        assertThat(result(o).get("notes")).isEqualTo(List.of(BulkResult.NOTES.get(3)));
    }

    @Test
    void aSourceUidMissingFromTheCopyUidSetIsNotFound() throws Exception {
        String h1 = deliver("INBOX", "a");
        String h2 = deliver("INBOX", "b");
        long u1 = decode(h1).uid();
        CopyMessagesTool.CopyCommand partial = (folder, uids, target) -> {
            Response tagged = new Response("A9 OK [COPYUID 77 " + u1 + " 31] done");
            return new UidCommands.Result(tagged, new Response[] {tagged});
        };
        List<Map<String, Object>> items = items(tool(partial).handle(ctx(A),
                args(acctHandle, List.of(h1, h2), DST, null)));
        assertThat(items).extracting(i -> i.get("status")).containsExactly("ok", "not_found");
    }

    // targets

    @Test
    void roleTargetIsNotFoundOnAServerWithoutSpecialUse() throws Exception {
        String h = deliver("INBOX", "a");
        ToolError e = error(tool().handle(ctx(A), args(acctHandle, List.of(h), null, "drafts")));
        assertThat(e.code()).isEqualTo(ErrorCode.FOLDER_NOT_FOUND);
        assertThat(e.details()).containsEntry("folder_role", "drafts");
        assertThat(snapshot(DST)).isEmpty();
    }

    @Test
    void missingVerbatimTargetIsFolderNotFoundAndNothingIsWritten() throws Exception {
        String h = deliver("INBOX", "a");
        ToolCallContext c = ctx(A);
        ToolError e = error(tool().handle(c, args(acctHandle, List.of(h), "Nope", null)));
        assertThat(e.code()).isEqualTo(ErrorCode.FOLDER_NOT_FOUND);
        assertThat(c.mutationStarted()).isFalse();
    }

    @Test
    void sameFolderIsRefusedBeforeAnyWriteForVerbatimAndForRoleTargets() throws Exception {
        String h = deliver("INBOX", "a");
        ToolError verbatim = error(noLeaseTool().handle(ctx(A), args(acctHandle, List.of(h), "INBOX", null)));
        assertThat(verbatim.code()).isEqualTo(ErrorCode.INVALID_ARGUMENT);
        assertThat(verbatim.details()).containsEntry("field", "target_folder").containsEntry("reason", "same_folder");
        ToolCallContext c = ctx(A);
        ToolError role = error(tool().handle(c, args(acctHandle, List.of(h), null, "inbox")));
        assertThat(role.details()).containsEntry("field", "target_folder_role")
                .containsEntry("reason", "same_folder");
        assertThat(c.mutationStarted()).isFalse();
        assertThat(snapshot("INBOX")).hasSize(1);
    }

    @Test
    void tryCreateOnTheCopyFailsEachGroupWithFolderNotFound() throws Exception {
        String h1 = deliver("INBOX", "a");
        String h2 = deliver("Src2", "b");
        CopyMessagesTool.CopyCommand tryCreate = (folder, uids, target) -> {
            Response tagged = new Response("A9 NO [TRYCREATE] no such mailbox");
            return new UidCommands.Result(tagged, new Response[] {tagged});
        };
        ToolOutcome o = tool(tryCreate).handle(ctx(A), args(acctHandle, List.of(h1, h2), DST, null));
        List<Map<String, Object>> items = items(o);
        assertThat(items).extracting(i -> i.get("status")).containsOnly("failed");
        @SuppressWarnings("unchecked")
        Map<String, Object> err = (Map<String, Object>) items.get(0).get("error");
        assertThat(err.get("code")).isEqualTo("FOLDER_NOT_FOUND");
        assertThat(result(o).get("notes")).isEqualTo(List.of(BulkResult.NOTES.get(6)));
    }

    // capability gate

    @Test
    void withoutUidplusTheCallIsUnsupportedAndNothingIsSelected() throws Exception {
        String h = deliver("INBOX", "a");
        IMAPStore store = mock(IMAPStore.class);
        ToolCallContext c = ctx(A);
        CopyMessagesTool t = new CopyMessagesTool(preflight, (k, cc, w) -> {
            ImapLease lease = LeaseTestSeams.lease(store, cc);
            LeaseTestSeams.forceCapabilities(lease, true, false);
            return w.apply(lease);
        }, handles, new FolderGroupRunner(Clock.systemUTC()), UidCommands::uidCopy);
        ToolError e = error(t.handle(c, args(acctHandle, List.of(h), DST, null)));
        assertThat(e.code()).isEqualTo(ErrorCode.UNSUPPORTED);
        assertThat(e.details()).containsEntry("capability", "UIDPLUS");
        verifyNoInteractions(store);
        assertThat(c.mutationStarted()).isFalse();
    }

    // deadline

    @Test
    void aDeadlineAfterTheMutationStartedIsOutcomeUnknownAndNotRetryable() throws Exception {
        String h = deliver("INBOX", "a");
        Clock farBeforeDeadline = Clock.fixed(Instant.now().minus(Duration.ofHours(1)), ZoneOffset.UTC);
        CopyMessagesTool.CopyCommand hang = (folder, uids, target) -> {
            try {
                Thread.sleep(30_000);
            } catch (InterruptedException e) {
                throw new MessagingException("interrupted");
            }
            throw new AssertionError("deadline must have fired");
        };
        CopyMessagesTool t = new CopyMessagesTool(preflight, (k, c, w) -> leases.withLease(k, c, w), handles,
                new FolderGroupRunner(farBeforeDeadline), hang);
        var executor = new ToolExecutor(Duration.ofMillis(800), new LogIds(KEYS));
        ToolCallContext c = ctx(A);
        ToolError e = error(executor.wrap(CopyMessagesTool.DEFINITION, t)
                .handle(c, args(acctHandle, List.of(h), DST, null)));
        assertThat(c.mutationStarted()).isTrue();
        assertThat(e.code()).isEqualTo(ErrorCode.OUTCOME_UNKNOWN);
        assertThat(e.retryable()).isFalse();
    }

    // tenant isolation

    @Test
    void aForeignTenantHandleAtAnyPositionIsInvalidHandleWithZeroIoAndNoChange() throws Exception {
        String own1 = deliver("INBOX", "a");
        String own2 = deliver("INBOX", "b");
        String foreign = deliver("INBOX", "c", false, B);
        List<String> inboxBefore = snapshot("INBOX");
        for (List<String> list : List.of(List.of(foreign, own1, own2), List.of(own1, own2, foreign))) {
            ToolOutcome o = noLeaseTool().handle(ctx(A), args(acctHandle, list, DST, null));
            ToolError e = error(o);
            assertThat(e.code()).isEqualTo(ErrorCode.INVALID_HANDLE);
            assertThat(e.details()).containsEntry("field", "handles[" + list.indexOf(foreign) + "]");
        }
        assertThat(snapshot("INBOX")).isEqualTo(inboxBefore);
        assertThat(snapshot(DST)).isEmpty();
    }

    @Test
    void aForeignTenantAccountHandleIsAccountNotFound() throws Exception {
        String h = deliver("INBOX", "a");
        String otherTenantAccount = InMemoryAccountRepository.handle(B, ACCT);
        assertThat(error(noLeaseTool().handle(ctx(new TenantId("tenant-c")),
                args(otherTenantAccount, List.of(h), DST, null))).code()).isEqualTo(ErrorCode.ACCOUNT_NOT_FOUND);
    }

    @Test
    void theLeaseIsTakenExactlyOncePerCall() throws Exception {
        String h1 = deliver("INBOX", "a");
        String h2 = deliver("Src2", "b");
        AtomicInteger taken = new AtomicInteger();
        CopyMessagesTool t = new CopyMessagesTool(preflight, (k, c, w) -> {
            taken.incrementAndGet();
            return leases.withLease(k, c, w);
        }, handles, new FolderGroupRunner(Clock.systemUTC()), UidCommands::uidCopy);
        t.handle(ctx(A), args(acctHandle, List.of(h1, h2), DST, null));
        assertThat(taken).hasValue(1);
    }
}
