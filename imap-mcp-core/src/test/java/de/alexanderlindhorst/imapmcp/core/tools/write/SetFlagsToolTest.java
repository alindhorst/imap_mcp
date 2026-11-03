package de.alexanderlindhorst.imapmcp.core.tools.write;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
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
import de.alexanderlindhorst.imapmcp.core.tenancy.AccountContext;
import de.alexanderlindhorst.imapmcp.core.tenancy.AccountId;
import de.alexanderlindhorst.imapmcp.core.tenancy.TenantId;
import de.alexanderlindhorst.imapmcp.core.tool.ToolCallContext;
import de.alexanderlindhorst.imapmcp.core.tool.ToolOutcome;
import de.alexanderlindhorst.imapmcp.core.tools.AccountArgument;
import de.alexanderlindhorst.imapmcp.core.tools.MessageLocator;
import jakarta.mail.Flags;
import jakarta.mail.Folder;
import jakarta.mail.Message;
import jakarta.mail.Session;
import jakarta.mail.Store;
import jakarta.mail.internet.MimeMessage;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import org.eclipse.angus.mail.imap.IMAPFolder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.slf4j.LoggerFactory;

/** set_flags execution against GreenMail: results, wire, no side effects, tenancy. */
class SetFlagsToolTest {

    static final TenantId A = new TenantId("tenant-a");
    static final TenantId B = new TenantId("tenant-b");
    static final AccountId ACCT = new AccountId("acct");
    static final AccountId RO = new AccountId("ro");
    static final KeyRing KEYS = new KeyRing(1, Map.of(1, new byte[32]));
    static final Session MAIL = Session.getInstance(new java.util.Properties());

    @RegisterExtension
    static final GreenMailExtension GREEN_MAIL = new GreenMailExtension(ServerSetupTest.IMAPS.dynamicPort());

    final MessageHandles handles = new MessageHandles(KEYS);
    final ListAppender<ILoggingEvent> trace = new ListAppender<>();
    final ListAppender<ILoggingEvent> callLog = new ListAppender<>();
    Logger traceLogger;
    Logger writeLogger;
    ImapSessionFactory sessions;
    StorePool pool;
    SetFlagsTool tool;
    String acctHandle;
    String roHandle;

    MailAccount account(AccountId id, boolean readOnly) {
        return new MailAccount(id, id.value(), null, "127.0.0.1", GREEN_MAIL.getImaps().getPort(),
                Security.IMPLICIT_TLS, Flavor.GENERIC, "user", new SecretRef("env:x"), readOnly);
    }

    InMemoryAccountRepository repo;
    StoreLeases leases;

    @BeforeEach
    void setUp() {
        GREEN_MAIL.setUser("user@localhost", "user", "secret");
        sessions = new ImapSessionFactory(ImapTimeouts.DEFAULTS, p -> {
            p.put("mail.imaps.ssl.trust", "*");
            p.put("mail.imaps.ssl.checkserveridentity", "false");
            return p;
        }, true);
        Map<AccountId, MailAccount> byId = Map.of(ACCT, account(ACCT, false), RO, account(RO, true));
        repo = new InMemoryAccountRepository(Map.of(A, List.of(byId.get(ACCT), byId.get(RO)),
                B, List.of(byId.get(ACCT))));
        pool = new StorePool(sessions, (t, a) -> Optional.of(new Secret("secret".toCharArray())),
                new CapabilityCache(), k -> byId.get(k.accountId()), PoolSettings.DEFAULTS);
        leases = new StoreLeases(pool, Duration.ofSeconds(10));
        tool = new SetFlagsTool(new AccountArgument(repo), new MessageLocator(handles), leases, Clock.systemUTC());
        acctHandle = InMemoryAccountRepository.handle(A, ACCT);
        roHandle = InMemoryAccountRepository.handle(A, RO);
        traceLogger = (Logger) LoggerFactory.getLogger("imapmcp.imap.trace");
        traceLogger.setLevel(Level.DEBUG);
        traceLogger.addAppender(trace);
        trace.start();
        writeLogger = (Logger) LoggerFactory.getLogger(WriteCallLog.class);
        writeLogger.addAppender(callLog);
        callLog.start();
    }

    @AfterEach
    void tearDown() {
        traceLogger.detachAppender(trace);
        writeLogger.detachAppender(callLog);
        pool.close();
        sessions.close();
    }

    // fixtures

    Store client() throws Exception {
        java.util.Properties props = new java.util.Properties();
        props.put("mail.imaps.ssl.trust", "*");
        props.put("mail.imaps.ssl.checkserveridentity", "false");
        Store s = Session.getInstance(props).getStore("imaps");
        s.connect("127.0.0.1", GREEN_MAIL.getImaps().getPort(), "user", "secret");
        return s;
    }

    /** Appends a message (with the given flags) to {@code folder} and returns its ref. */
    MessageRef deliver(String folder, Flags flags) throws Exception {
        try (Store s = client()) {
            IMAPFolder f = (IMAPFolder) s.getFolder(folder);
            if (!f.exists()) {
                f.create(Folder.HOLDS_MESSAGES);
            }
            f.open(Folder.READ_WRITE);
            MimeMessage m = new MimeMessage(MAIL);
            m.setFrom("a@localhost");
            m.setRecipients(Message.RecipientType.TO, "user@localhost");
            m.setSubject("fixture");
            m.setText("body");
            m.saveChanges();
            m.setFlags(flags, true);
            f.appendMessages(new Message[] {m});
            Message last = f.getMessage(f.getMessageCount());
            MessageRef ref = new MessageRef(folder, f.getUIDValidity(), f.getUID(last));
            f.close(false);
            return ref;
        }
    }

    String handle(MessageRef ref) {
        return handles.encode(A, ACCT, ref);
    }

    /** folder -> uid -> flags, for every folder; read with EXAMINE. */
    Map<String, Map<Long, String>> snapshot() throws Exception {
        Map<String, Map<Long, String>> out = new TreeMap<>();
        try (Store s = client()) {
            for (Folder f : s.getDefaultFolder().list("*")) {
                IMAPFolder imap = (IMAPFolder) f;
                imap.open(Folder.READ_ONLY);
                Map<Long, String> m = new TreeMap<>();
                for (Message msg : imap.getMessages()) {
                    Flags fl = msg.getFlags();
                    List<String> names = new ArrayList<>();
                    for (Flags.Flag sf : fl.getSystemFlags()) {
                        names.add(sf.toString());
                    }
                    names.addAll(List.of(fl.getUserFlags()));
                    java.util.Collections.sort(names);
                    m.put(imap.getUID(msg), String.join(",", names));
                }
                imap.close(false);
                out.put(f.getFullName(), m);
            }
        }
        return out;
    }

    Flags flagsOf(MessageRef ref) throws Exception {
        try (Store s = client()) {
            IMAPFolder f = (IMAPFolder) s.getFolder(ref.folder());
            f.open(Folder.READ_ONLY);
            Flags fl = f.getMessageByUID(ref.uid()).getFlags();
            f.close(false);
            return fl;
        }
    }

    ToolOutcome call(TenantId tenant, String account, List<String> hs, Map<String, Object> change) {
        return call(tool, tenant, account, hs, change);
    }

    ToolOutcome call(SetFlagsTool t, TenantId tenant, String account, List<String> hs, Map<String, Object> change) {
        Map<String, Object> args = new LinkedHashMap<>();
        args.put("account", account);
        args.put("handles", hs);
        args.putAll(change);
        return t.handle(new ToolCallContext(new AccountContext(tenant), "rid"), args);
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
    static List<Map<String, Object>> results(Map<String, Object> out) {
        return (List<Map<String, Object>>) out.get("results");
    }

    List<String> traceLines() {
        return new ArrayList<>(trace.list.stream().map(ILoggingEvent::getFormattedMessage).toList());
    }

    // acceptance

    @Test
    void addsAndRemovesFlagsOnThreeHandlesInTwoFoldersAndLogsOneStoreLine() throws Exception {
        MessageRef a = deliver("INBOX", new Flags(Flags.Flag.ANSWERED));
        MessageRef b = deliver("INBOX", new Flags(Flags.Flag.ANSWERED));
        MessageRef c = deliver("Other", new Flags(Flags.Flag.ANSWERED));
        List<String> hs = List.of(handle(a), handle(c), handle(b));
        trace.list.clear();
        callLog.list.clear();
        Map<String, Object> out = ok(call(A, acctHandle, hs, Map.of("seen", true, "flagged", true, "answered", false)));
        assertThat(results(out)).extracting(m -> m.get("handle")).containsExactlyElementsOf(hs);
        assertThat(results(out)).extracting(m -> m.get("status")).containsOnly("ok");
        assertThat(out).doesNotContainKey("target_folder").containsEntry("account", acctHandle);
        assertThat(out.get("notes")).isEqualTo(List.of());
        for (MessageRef r : List.of(a, b, c)) {
            Flags f = flagsOf(r);
            assertThat(f.contains(Flags.Flag.SEEN)).isTrue();
            assertThat(f.contains(Flags.Flag.FLAGGED)).isTrue();
            assertThat(f.contains(Flags.Flag.ANSWERED)).isFalse();
        }
        List<String> stores = traceLines().stream().filter(l -> l.contains("STORE")).toList();
        assertThat(stores).hasSize(4); // one add and one remove per group, two groups
        assertThat(stores).allMatch(l -> l.contains("UID STORE")
                && (l.contains("+FLAGS.SILENT") || l.contains("-FLAGS.SILENT")));
        assertThat(stores.stream().filter(l -> l.contains("+FLAGS.SILENT")).count()).isEqualTo(2);
        assertThat(traceLines()).noneMatch(l -> l.contains("EXPUNGE"));
        assertThat(callLog.list).hasSize(1);
        assertThat(callLog.list.get(0).getFormattedMessage()).contains("tool=set_flags").contains("path=store")
                .contains("groups=2").contains("ok=3");
    }

    @Test
    void onlyTheRequestedFlagsOfTheAddressedUidsChange() throws Exception {
        MessageRef a = deliver("INBOX", new Flags());
        MessageRef other = deliver("INBOX", new Flags(Flags.Flag.ANSWERED));
        deliver("Other", new Flags(Flags.Flag.FLAGGED));
        Map<String, Map<Long, String>> before = snapshot();
        ok(call(A, acctHandle, List.of(handle(a)), Map.of("seen", true)));
        Map<String, Map<Long, String>> after = snapshot();
        Map<String, Map<Long, String>> expected = new TreeMap<>();
        before.forEach((k, v) -> expected.put(k, new TreeMap<>(v)));
        String was = before.get("INBOX").get(a.uid());
        expected.get("INBOX").put(a.uid(), was.isEmpty() ? "\\Seen" : was + ",\\Seen");
        assertThat(after.keySet()).isEqualTo(before.keySet());
        assertThat(after.get("INBOX").keySet()).isEqualTo(before.get("INBOX").keySet());
        assertThat(after.get("INBOX").get(other.uid())).isEqualTo(before.get("INBOX").get(other.uid()));
        assertThat(after.get("Other")).isEqualTo(before.get("Other"));
        assertThat(flagsOf(a).contains(Flags.Flag.SEEN)).isTrue();
        assertThat(flagsOf(other).contains(Flags.Flag.SEEN)).isFalse();
    }

    @Test
    void deletedFalseClearsOnlyTheAddressedUidAndExpungesNothing() throws Exception {
        MessageRef a = deliver("INBOX", new Flags(Flags.Flag.DELETED));
        MessageRef other = deliver("INBOX", new Flags(Flags.Flag.DELETED));
        trace.list.clear();
        ok(call(A, acctHandle, List.of(handle(a)), Map.of("deleted", false)));
        assertThat(flagsOf(a).contains(Flags.Flag.DELETED)).isFalse();
        assertThat(flagsOf(other).contains(Flags.Flag.DELETED)).isTrue();
        assertThat(traceLines()).noneMatch(l -> l.contains("EXPUNGE"));
        assertThat(snapshot().get("INBOX")).hasSize(2);
    }

    @Test
    void keywordIsAddedAndRemovedWhereTheServerAllowsIt() throws Exception {
        MessageRef a = deliver("INBOX", new Flags());
        Map<String, Object> out = ok(call(A, acctHandle, List.of(handle(a)), Map.of("add_keywords", List.of("Todo"))));
        assertThat(results(out)).extracting(m -> m.get("status")).containsExactly("ok");
        assertThat(flagsOf(a).getUserFlags()).containsExactlyInAnyOrder("Todo");
        ok(call(A, acctHandle, List.of(handle(a)), Map.of("remove_keywords", List.of("todo"))));
        assertThat(flagsOf(a).getUserFlags()).isEmpty();
    }

    @Test
    void keywordWithoutPermanentFlagsFailsOnlyThatGroupAsUnsupported() throws Exception {
        MessageRef a = deliver("INBOX", new Flags());
        MessageRef c = deliver("Other", new Flags());
        // stub: INBOX has no keyword support, every other folder has \*
        SetFlagsTool stubbed = new SetFlagsTool(new AccountArgument(repo), new MessageLocator(handles), leases,
                Clock.systemUTC(), f -> f.getFullName().equals("INBOX")
                        ? new Flags(Flags.Flag.SEEN) : new Flags(Flags.Flag.USER));
        trace.list.clear();
        Map<String, Object> out = ok(call(stubbed, A, acctHandle, List.of(handle(a), handle(c)),
                Map.of("seen", true, "add_keywords", List.of("Todo"))));
        List<Map<String, Object>> r = results(out);
        assertThat(r.get(0)).containsEntry("status", "failed");
        @SuppressWarnings("unchecked")
        Map<String, Object> err = (Map<String, Object>) r.get(0).get("error");
        assertThat(err).containsEntry("code", "UNSUPPORTED");
        assertThat(err.toString()).contains("keywords");
        assertThat(r.get(1)).containsEntry("status", "ok");
        assertThat(flagsOf(a).contains(Flags.Flag.SEEN)).isFalse(); // the group failed as a whole
        assertThat(flagsOf(c).contains(Flags.Flag.SEEN)).isTrue();
        assertThat(traceLines().stream().filter(l -> l.contains("STORE")).count()).isEqualTo(1);
    }

    @Test
    void anExpungedHandleIsNotFound() throws Exception {
        MessageRef a = deliver("INBOX", new Flags());
        MessageRef gone = deliver("INBOX", new Flags());
        try (Store s = client()) {
            IMAPFolder f = (IMAPFolder) s.getFolder("INBOX");
            f.open(Folder.READ_WRITE);
            f.getMessageByUID(gone.uid()).setFlag(Flags.Flag.DELETED, true);
            f.close(true);
        }
        Map<String, Object> out = ok(call(A, acctHandle, List.of(handle(a), handle(gone)), Map.of("seen", true)));
        assertThat(results(out)).extracting(m -> m.get("status")).containsExactly("ok", "not_found");
        assertThat((List<?>) out.get("notes")).hasSize(1);
        assertThat((List<?>) out.get("notes")).isEqualTo(new BulkResultNotes().forNotFound());
    }

    @Test
    void uidValidityMismatchIsStaleReference() throws Exception {
        MessageRef a = deliver("INBOX", new Flags());
        MessageRef wrong = new MessageRef(a.folder(), a.uidValidity() + 1, a.uid());
        Map<String, Object> out = ok(call(A, acctHandle, List.of(handle(wrong)), Map.of("seen", true)));
        assertThat(results(out).get(0)).containsEntry("status", "stale_reference")
                .containsEntry("reason", "uidvalidity_changed");
        assertThat(flagsOf(a).contains(Flags.Flag.SEEN)).isFalse();
    }

    @Test
    void aDeletedFolderIsStaleFolderMissing() throws Exception {
        MessageRef a = deliver("Temp", new Flags());
        try (Store s = client()) {
            s.getFolder("Temp").delete(true);
        }
        Map<String, Object> out = ok(call(A, acctHandle, List.of(handle(a)), Map.of("seen", true)));
        assertThat(results(out).get(0)).containsEntry("status", "stale_reference")
                .containsEntry("reason", "folder_missing");
    }

    @Test
    void aHandleOfAnotherTenantAtAnyPositionIsInvalidHandleWithoutIoOrChange() throws Exception {
        MessageRef a = deliver("INBOX", new Flags());
        MessageRef b = deliver("INBOX", new Flags());
        String foreign = handles.encode(B, ACCT, b);
        long created = pool.createdCount();
        Map<String, Map<Long, String>> before = snapshot();
        trace.list.clear();
        Map<String, Object> e = error(call(A, acctHandle, List.of(handle(a), foreign), Map.of("seen", true)));
        assertThat(e).containsEntry("code", "INVALID_HANDLE");
        assertThat(e.toString()).contains("handles[1]");
        assertThat(pool.createdCount()).isEqualTo(created);
        assertThat(traceLines()).isEmpty();
        assertThat(snapshot()).isEqualTo(before);
        // the lease factory is never called
        StoreLeases never = mock(StoreLeases.class);
        SetFlagsTool guarded = new SetFlagsTool(new AccountArgument(repo), new MessageLocator(handles), never,
                Clock.systemUTC());
        assertThat(error(call(guarded, A, acctHandle, List.of(foreign), Map.of("seen", true))))
                .containsEntry("code", "INVALID_HANDLE");
        verifyNoInteractions(never);
    }

    @Test
    void readOnlyAccountIsPermissionDeniedWithoutIo() throws Exception {
        MessageRef a = deliver("INBOX", new Flags());
        StoreLeases never = mock(StoreLeases.class);
        SetFlagsTool guarded = new SetFlagsTool(new AccountArgument(repo), new MessageLocator(handles), never,
                Clock.systemUTC());
        String h = handles.encode(A, RO, a);
        Map<String, Object> e = error(call(guarded, A, roHandle, List.of(h), Map.of("seen", true)));
        assertThat(e).containsEntry("code", "PERMISSION_DENIED");
        assertThat(e.toString()).contains("account_read_only");
        verifyNoInteractions(never);
    }

    /** The fixed not_found sentence from the shared note list (BulkResult.NOTES index 4). */
    static final class BulkResultNotes {
        List<String> forNotFound() {
            return List.of(BulkResult.NOTES.get(4));
        }
    }
}
