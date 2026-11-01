package de.alexanderlindhorst.imapmcp.core.tools;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.icegreen.greenmail.util.GreenMail;
import com.icegreen.greenmail.util.ServerSetupTest;
import de.alexanderlindhorst.imapmcp.core.account.InMemoryAccountRepository;
import de.alexanderlindhorst.imapmcp.core.account.MailAccount;
import de.alexanderlindhorst.imapmcp.core.account.MailAccount.Flavor;
import de.alexanderlindhorst.imapmcp.core.account.MailAccount.Security;
import de.alexanderlindhorst.imapmcp.core.account.PoolKey;
import de.alexanderlindhorst.imapmcp.core.account.ResolvedAccount;
import de.alexanderlindhorst.imapmcp.core.account.Secret;
import de.alexanderlindhorst.imapmcp.core.account.SecretRef;
import de.alexanderlindhorst.imapmcp.core.error.ErrorCode;
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
import jakarta.mail.Folder;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.eclipse.angus.mail.imap.IMAPFolder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

class ListFoldersToolTest {

    static final TenantId A = new TenantId("tenant-a");
    static final TenantId B = new TenantId("tenant-b");

    GreenMail greenMail;
    ImapSessionFactory sessions;
    StorePool pool;
    StoreLeases leases;
    InMemoryAccountRepository repo;
    final ListAppender<ILoggingEvent> trace = new ListAppender<>();
    Logger traceLogger;

    MailAccount account(String id, String user) {
        return new MailAccount(new AccountId(id), id, null, "127.0.0.1", greenMail.getImaps().getPort(),
                Security.IMPLICIT_TLS, Flavor.GENERIC, user, new SecretRef("env:" + id));
    }

    @BeforeEach
    void setUp() {
        greenMail = new GreenMail(ServerSetupTest.IMAPS.dynamicPort());
        greenMail.start();
        greenMail.setUser("alpha@localhost", "alpha", "pw");
        greenMail.setUser("beta@localhost", "beta", "pw");
        sessions = new ImapSessionFactory(ImapTimeouts.DEFAULTS, p -> {
            p.put("mail.imaps.ssl.trust", "*");
            p.put("mail.imaps.ssl.checkserveridentity", "false");
            return p;
        }, true);
        MailAccount alpha = account("alpha", "alpha");
        MailAccount beta = account("beta", "beta");
        repo = new InMemoryAccountRepository(Map.of(A, List.of(alpha), B, List.of(beta)));
        Map<PoolKey, MailAccount> byKey = new HashMap<>();
        byKey.put(PoolKey.of(new AccountContext(A), repo.findByTenant(A).get(0)), alpha);
        byKey.put(PoolKey.of(new AccountContext(B), repo.findByTenant(B).get(0)), beta);
        pool = new StorePool(sessions, (t, a) -> Optional.of(new Secret("pw".toCharArray())), new CapabilityCache(),
                byKey::get, PoolSettings.DEFAULTS);
        leases = new StoreLeases(pool, Duration.ofSeconds(10));
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
        greenMail.stop();
    }

    ListFoldersTool tool(int max) {
        return new ListFoldersTool(new AccountArgument(repo), leases, max);
    }

    String handleA() {
        return repo.findByTenant(A).get(0).handle();
    }

    void mailbox(String user, String name) throws Exception {
        greenMail.getManagers().getImapHostManager().createMailbox(
                greenMail.getUserManager().getUserByEmail(user + "@localhost"), name);
    }

    ToolOutcome call(ListFoldersTool t, TenantId tenant, Map<String, Object> args) {
        return t.handle(new ToolCallContext(new AccountContext(tenant), "rid"), args);
    }

    @SuppressWarnings("unchecked")
    Map<String, Object> ok(ToolOutcome o) {
        assertThat(o).isInstanceOf(ToolOutcome.Success.class);
        return (Map<String, Object>) ((ToolOutcome.Success) o).structured();
    }

    @SuppressWarnings("unchecked")
    List<Map<String, Object>> folders(Map<String, Object> out) {
        return (List<Map<String, Object>>) out.get("folders");
    }

    // AC1

    @Test
    void sortedHierarchyWithParentNameDelimiterAndDecodedNonAscii() throws Exception {
        mailbox("alpha", "Zeta");
        mailbox("alpha", "Archive");
        mailbox("alpha", "Archive.2025");
        mailbox("alpha", "Entwürfe");
        Map<String, Object> out = ok(call(tool(100), A, Map.of("account", handleA())));
        List<Map<String, Object>> f = folders(out);
        assertThat(f).extracting(m -> m.get("folder")).containsExactly("INBOX", "Archive", "Archive.2025",
                "Entwürfe", "Zeta");
        Map<String, Object> child = f.get(2);
        assertThat(child).containsEntry("name", "2025").containsEntry("parent", "Archive").containsEntry("delimiter", ".");
        assertThat(f.get(1)).containsEntry("name", "Archive").containsEntry("parent", null);
        assertThat(f.get(3)).containsEntry("name", "Entwürfe");
        assertThat(out).containsEntry("account", handleA());
    }

    // AC2 + counts placeholder

    @Test
    void rolesNullCountsNullAndNotCompleteOnGreenMail() throws Exception {
        mailbox("alpha", "Sent");
        Map<String, Object> out = ok(call(tool(100), A, Map.of("account", handleA())));
        for (Map<String, Object> m : folders(out)) {
            assertThat(m).containsEntry("role", null).containsEntry("total", null).containsEntry("unread", null)
                    .containsEntry("selectable", true);
            assertThat(m.get("attributes")).isInstanceOf(List.class);
        }
        assertThat(out).containsEntry("folders_truncated", false).containsEntry("counts_complete", false);
    }

    // AC3

    @Test
    void capOfTwoTruncates() throws Exception {
        mailbox("alpha", "A1");
        mailbox("alpha", "A2");
        Map<String, Object> out = ok(call(tool(2), A, Map.of("account", handleA())));
        assertThat(folders(out)).extracting(m -> m.get("folder")).containsExactly("INBOX", "A1");
        assertThat(out).containsEntry("folders_truncated", true);
    }

    @Test
    void defaultCapIs100() throws Exception {
        for (int i = 0; i < 99; i++) {
            mailbox("alpha", String.format("F%03d", i));
        }
        ListFoldersTool t = new ListFoldersTool(new AccountArgument(repo), leases); // INBOX + 99 = 100
        Map<String, Object> out = ok(call(t, A, Map.of("account", handleA())));
        assertThat(folders(out)).hasSize(100);
        assertThat(out).containsEntry("folders_truncated", false);
        mailbox("alpha", "F099"); // 101
        out = ok(call(t, A, Map.of("account", handleA())));
        assertThat(folders(out)).hasSize(100);
        assertThat(out).containsEntry("folders_truncated", true);
        assertThat(folders(out).get(99)).containsEntry("folder", "F098");
    }

    // AC4

    @Test
    void issuesOneListAndNoSelectOrExamine() throws Exception {
        mailbox("alpha", "Work");
        ok(call(tool(100), A, Map.of("account", handleA())));
        List<String> lines = trace.list.stream().map(ILoggingEvent::getFormattedMessage).toList();
        assertThat(lines).anyMatch(l -> l.matches("[A-Z]+\\d+ LIST .*"));
        assertThat(lines.stream().filter(l -> l.matches("[A-Z]+\\d+ LIST .*")).count()).isEqualTo(1);
        assertThat(lines).noneMatch(l -> l.matches("[A-Z]+\\d+ (SELECT|EXAMINE|STORE|EXPUNGE|FETCH|APPEND|COPY|CREATE|DELETE) .*"));
    }

    // AC5

    @Test
    void foreignUnknownAndMalformedHandlesGiveIdenticalAccountNotFound() {
        String foreign = repo.findByTenant(B).get(0).handle();
        var e1 = ((ToolOutcome.Failure) call(tool(100), A, Map.of("account", foreign))).error();
        var e2 = ((ToolOutcome.Failure) call(tool(100), A, Map.of("account", "acc_aaaaaaaaaaaaaaaaaaaaaaaaaa"))).error();
        var e3 = ((ToolOutcome.Failure) call(tool(100), A, Map.of("account", "nonsense"))).error();
        assertThat(e1.code()).isEqualTo(ErrorCode.ACCOUNT_NOT_FOUND);
        assertThat(e1.toMap()).isEqualTo(e2.toMap()).isEqualTo(e3.toMap());
    }

    // AC7 (name sanitising, byte limit) with mocked listing

    private static IMAPFolder mocked(String full, char sep, String... attrs) {
        IMAPFolder f = mock(IMAPFolder.class);
        try {
            when(f.getFullName()).thenReturn(full);
            when(f.getSeparator()).thenReturn(sep);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
        when(f.getAttributes()).thenReturn(attrs);
        return f;
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> entries(Folder... listed) {
        return (List<Map<String, Object>>) ListFoldersTool.describe(listed, 100).get("folders");
    }

    @Test
    void displayNameIsSanitisedButFolderIdentifierIsVerbatim() {
        String full = "Inbox/ev‮il\tname";
        Map<String, Object> m = entries(mocked(full, '/'), mocked("INBOX", '/')).get(1);
        assertThat(m.get("folder")).isEqualTo(full);
        assertThat(m.get("name")).isEqualTo("evil name");
        assertThat(m.get("parent")).isEqualTo("Inbox");
    }

    @Test
    void displayNameIsCappedAt255CodePoints() {
        String seg = "x".repeat(300);
        Map<String, Object> m = entries(mocked("P/" + seg, '/')).get(0);
        assertThat(((String) m.get("name")).codePointCount(0, ((String) m.get("name")).length())).isEqualTo(255);
        assertThat(m.get("folder")).isEqualTo("P/" + seg);
    }

    @Test
    void folderOverThousandBytesIsListedButNotSelectable() {
        String over = "ä".repeat(501);
        String exact = "ä".repeat(500);
        List<Map<String, Object>> f = entries(mocked(over, '/', "\\HasNoChildren"), mocked(exact, '/'));
        Map<String, Object> o = f.stream().filter(m -> over.equals(m.get("folder"))).findFirst().orElseThrow();
        Map<String, Object> e = f.stream().filter(m -> exact.equals(m.get("folder"))).findFirst().orElseThrow();
        assertThat(o).containsEntry("selectable", false);
        assertThat(o.get("attributes")).isEqualTo(List.of("\\HasNoChildren"));
        assertThat(e).containsEntry("selectable", true);
    }

    @Test
    void noselectNonExistentRolesAndNilDelimiter() {
        List<Map<String, Object>> f = entries(mocked("A", '￿', "\\Noselect"), mocked("B", '￿', "\\NonExistent"),
                mocked("S", '￿', "\\Sent"), mocked("INBOX", '￿'));
        assertThat(f).extracting(m -> m.get("folder")).containsExactly("INBOX", "A", "B", "S");
        assertThat(f.get(0)).containsEntry("role", "inbox").containsEntry("delimiter", null).containsEntry("parent", null);
        assertThat(f.get(1)).containsEntry("selectable", false);
        assertThat(f.get(2)).containsEntry("selectable", false);
        assertThat(f.get(3)).containsEntry("role", "sent").containsEntry("selectable", true);
    }

    @Test
    void sortIsCodePointOrder() {
        List<Map<String, Object>> f = entries(mocked("😀", '/'), mocked("～", '/'), mocked("INBOX", '/'));
        assertThat(f).extracting(m -> m.get("folder")).containsExactly("INBOX", "～", "😀");
    }
}
