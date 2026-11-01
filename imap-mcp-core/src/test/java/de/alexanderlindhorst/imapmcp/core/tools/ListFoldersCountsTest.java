package de.alexanderlindhorst.imapmcp.core.tools;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.icegreen.greenmail.user.GreenMailUser;
import com.icegreen.greenmail.util.GreenMail;
import com.icegreen.greenmail.util.GreenMailUtil;
import com.icegreen.greenmail.util.ServerSetupTest;
import de.alexanderlindhorst.imapmcp.core.account.InMemoryAccountRepository;
import de.alexanderlindhorst.imapmcp.core.account.MailAccount;
import de.alexanderlindhorst.imapmcp.core.account.MailAccount.Flavor;
import de.alexanderlindhorst.imapmcp.core.account.MailAccount.Security;
import de.alexanderlindhorst.imapmcp.core.account.PoolKey;
import de.alexanderlindhorst.imapmcp.core.account.Secret;
import de.alexanderlindhorst.imapmcp.core.account.SecretRef;
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
import jakarta.mail.internet.MimeMessage;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

/** list_folders with the default STATUS count enricher (#24). */
class ListFoldersCountsTest {

    static final TenantId T = new TenantId("t");

    GreenMail greenMail;
    ImapSessionFactory sessions;
    StorePool pool;
    StoreLeases leases;
    InMemoryAccountRepository repo;
    PoolKey key;
    GreenMailUser user;
    final ListAppender<ILoggingEvent> trace = new ListAppender<>();
    Logger traceLogger;

    @BeforeEach
    void setUp() {
        greenMail = new GreenMail(ServerSetupTest.IMAPS.dynamicPort());
        greenMail.start();
        user = greenMail.setUser("alpha@localhost", "alpha", "pw");
        sessions = new ImapSessionFactory(ImapTimeouts.DEFAULTS, p -> {
            p.put("mail.imaps.ssl.trust", "*");
            p.put("mail.imaps.ssl.checkserveridentity", "false");
            return p;
        }, true);
        MailAccount alpha = new MailAccount(new AccountId("alpha"), "alpha", null, "127.0.0.1",
                greenMail.getImaps().getPort(), Security.IMPLICIT_TLS, Flavor.GENERIC, "alpha", new SecretRef("env:a"));
        repo = new InMemoryAccountRepository(Map.of(T, List.of(alpha)));
        key = PoolKey.of(new AccountContext(T), repo.findByTenant(T).get(0));
        pool = new StorePool(sessions, (t, a) -> Optional.of(new Secret("pw".toCharArray())), new CapabilityCache(),
                k -> alpha, PoolSettings.DEFAULTS);
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

    void mailbox(String name) throws Exception {
        greenMail.getManagers().getImapHostManager().createMailbox(user, name);
    }

    void message(String folder, boolean seen) throws Exception {
        MimeMessage m = GreenMailUtil.createTextEmail("alpha@localhost", "from@localhost", "s", "b", greenMail.getImaps().getServerSetup());
        greenMail.getManagers().getImapHostManager().getFolder(user, folder)
                .appendMessage(m, seen ? new Flags(Flags.Flag.SEEN) : new Flags(), new Date());
    }

    List<String> lines() {
        return trace.list.stream().map(ILoggingEvent::getFormattedMessage).toList();
    }

    long count(String regex) {
        return lines().stream().filter(l -> l.matches(regex)).count();
    }

    @SuppressWarnings("unchecked")
    Map<String, Object> run(ListFoldersTool t, ToolCallContext ctx, boolean includeCounts) {
        Map<String, Object> args = new LinkedHashMap<>();
        args.put("account", repo.findByTenant(T).get(0).handle());
        args.put("include_counts", includeCounts);
        ToolOutcome o = t.handle(ctx, args);
        assertThat(o).isInstanceOf(ToolOutcome.Success.class);
        return (Map<String, Object>) ((ToolOutcome.Success) o).structured();
    }

    ToolCallContext ctx() {
        return new ToolCallContext(new AccountContext(T), "rid");
    }

    @SuppressWarnings("unchecked")
    Map<String, Map<String, Object>> byFolder(Map<String, Object> out) {
        Map<String, Map<String, Object>> m = new LinkedHashMap<>();
        for (Map<String, Object> f : (List<Map<String, Object>>) out.get("folders")) {
            m.put((String) f.get("folder"), f);
        }
        return m;
    }

    // AC1

    @Test
    void totalAndUnreadFromStatus() throws Exception {
        message("INBOX", true);
        message("INBOX", false);
        message("INBOX", false);
        Map<String, Object> out = run(tool(100), ctx(), true);
        assertThat(byFolder(out).get("INBOX")).containsEntry("total", 3).containsEntry("unread", 2);
        assertThat(out).containsEntry("counts_complete", true);
    }

    // AC2 + non-selectable folders + AC6 no side effects

    @Test
    void oneStatusPerSelectableFolderAndNoSelectOrExamineOrStore() throws Exception {
        mailbox("Work");
        mailbox("Parent.Child"); // Parent is \Noselect
        message("Work", false);
        Map<String, Object> out = run(tool(100), ctx(), true);
        Map<String, Map<String, Object>> f = byFolder(out);
        assertThat(f.keySet()).containsExactly("INBOX", "Parent", "Parent.Child", "Work");
        assertThat(f.get("Parent")).containsEntry("selectable", false).containsEntry("total", null).containsEntry("unread", null);
        assertThat(f.get("Work")).containsEntry("total", 1).containsEntry("unread", 1);
        assertThat(f.get("Parent.Child")).containsEntry("total", 0).containsEntry("unread", 0);
        assertThat(out).containsEntry("counts_complete", true); // non-selectable does not make it incomplete
        assertThat(count("[A-Z]+\\d+ STATUS .*")).isEqualTo(3);
        assertThat(lines()).filteredOn(l -> l.matches("[A-Z]+\\d+ STATUS .*"))
                .allMatch(l -> l.contains("MESSAGES") && l.contains("UNSEEN"));
        assertThat(lines()).noneMatch(l -> l.matches("[A-Z]+\\d+ (SELECT|EXAMINE|STORE|EXPUNGE|FETCH|APPEND|COPY|CREATE|DELETE) .*"));
        // reading the counts did not mark anything as read
        assertThat(run(tool(100), ctx(), true).get("counts_complete")).isEqualTo(true);
        assertThat(byFolder(run(tool(100), ctx(), true)).get("Work")).containsEntry("unread", 1);
    }

    // AC3

    @Test
    void includeCountsFalseIssuesNoStatus() throws Exception {
        message("INBOX", false);
        Map<String, Object> out = run(tool(100), ctx(), false);
        assertThat(byFolder(out).get("INBOX")).containsEntry("total", null).containsEntry("unread", null);
        assertThat(out).containsEntry("counts_complete", false);
        assertThat(count("[A-Z]+\\d+ STATUS .*")).isZero();
    }

    // AC4: a folder that is gone between LIST and STATUS

    @Test
    void statusFailureNullsOnlyThatFolder() throws Exception {
        message("INBOX", false);
        List<Map<String, Object>> entries = new ArrayList<>();
        entries.add(entry("Ghost"));
        entries.add(entry("INBOX"));
        boolean complete = leases.withLease(key, ctx(), lease -> {
            boolean c = new StatusCountEnricher().enrich(lease, ctx(), entries);
            assertThat(lease.store().isConnected()).isTrue(); // a NO answer does not break the lease
            return c;
        });
        assertThat(complete).isFalse();
        assertThat(entries.get(0)).containsEntry("total", null).containsEntry("unread", null);
        assertThat(entries.get(1)).containsEntry("total", 1).containsEntry("unread", 1);
    }

    private static Map<String, Object> entry(String folder) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("folder", folder);
        m.put("selectable", true);
        m.put("total", null);
        m.put("unread", null);
        return m;
    }

    // AC5

    @Test
    void passedDeadlineStopsCountingWithoutTimeout() throws Exception {
        message("INBOX", false);
        mailbox("Work");
        ToolCallContext c = new ToolCallContext(new AccountContext(T), "rid", Instant.now().minusSeconds(1));
        Map<String, Object> out = run(tool(100), c, true);
        assertThat(byFolder(out).get("INBOX")).containsEntry("total", null);
        assertThat(out).containsEntry("counts_complete", false);
        assertThat(count("[A-Z]+\\d+ STATUS .*")).isZero();
    }

    @Test
    void cutoffIsThreeQuartersOfTheRemainingTime() {
        Instant now = Instant.parse("2026-01-01T00:00:00Z");
        assertThat(StatusCountEnricher.cutoff(now, Optional.of(now.plusSeconds(100)))).contains(now.plusSeconds(75));
        assertThat(StatusCountEnricher.cutoff(now, Optional.empty())).isEmpty();
        assertThat(StatusCountEnricher.cutoff(now, Optional.of(now.minusSeconds(5)))).contains(now);
    }

    // AC7

    @Test
    void cappedListGetsExactlyThatManyStatusCommands() throws Exception {
        mailbox("A1");
        mailbox("A2");
        mailbox("A3");
        Map<String, Object> out = run(tool(2), ctx(), true);
        assertThat(out).containsEntry("folders_truncated", true).containsEntry("counts_complete", true);
        assertThat(count("[A-Z]+\\d+ STATUS .*")).isEqualTo(2);
    }
}
