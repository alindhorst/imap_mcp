package de.alexanderlindhorst.imapmcp.core.tools.write;

import static org.assertj.core.api.Assertions.assertThat;

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
import de.alexanderlindhorst.imapmcp.core.account.Secret;
import de.alexanderlindhorst.imapmcp.core.account.SecretRef;
import de.alexanderlindhorst.imapmcp.core.error.ErrorCode;
import de.alexanderlindhorst.imapmcp.core.imap.CapabilityCache;
import de.alexanderlindhorst.imapmcp.core.imap.ImapSessionFactory;
import de.alexanderlindhorst.imapmcp.core.imap.ImapTimeouts;
import de.alexanderlindhorst.imapmcp.core.imap.pool.ImapLease;
import de.alexanderlindhorst.imapmcp.core.imap.pool.PoolSettings;
import de.alexanderlindhorst.imapmcp.core.imap.pool.StoreLeases;
import de.alexanderlindhorst.imapmcp.core.imap.pool.StorePool;
import de.alexanderlindhorst.imapmcp.core.tenancy.AccountContext;
import de.alexanderlindhorst.imapmcp.core.tenancy.AccountId;
import de.alexanderlindhorst.imapmcp.core.tenancy.TenantId;
import de.alexanderlindhorst.imapmcp.core.tool.ToolCallContext;
import de.alexanderlindhorst.imapmcp.core.tool.ToolOutcome;
import de.alexanderlindhorst.imapmcp.core.tools.AccountArgument;
import de.alexanderlindhorst.imapmcp.core.tools.ListFoldersTool;
import jakarta.mail.MessagingException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

/** create_folder against GreenMail through the real LIST / NAMESPACE / CREATE / SUBSCRIBE path. */
class CreateFolderToolTest {

    static final TenantId A = new TenantId("tenant-a");
    static final TenantId B = new TenantId("tenant-b");

    /** The real command class, with a record of what was sent. */
    static final class CountingCommands implements CreateFolderTool.Commands {
        final List<String> sent = new ArrayList<>();

        @Override
        public UidCommands.Result create(ImapLease lease, String name) throws MessagingException {
            sent.add("CREATE");
            return UidCommands.create(lease, name);
        }

        @Override
        public UidCommands.Result subscribe(ImapLease lease, String name) throws MessagingException {
            sent.add("SUBSCRIBE");
            return UidCommands.subscribe(lease, name);
        }
    }

    GreenMail greenMail;
    ImapSessionFactory sessions;
    StorePool pool;
    StoreLeases leases;
    InMemoryAccountRepository repo;
    CountingCommands commands;
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
        commands = new CountingCommands();
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

    String handleA() {
        return repo.findByTenant(A).get(0).handle();
    }

    ToolOutcome call(TenantId tenant, String account, String parent, String name) {
        Map<String, Object> args = new HashMap<>();
        args.put("account", account);
        args.put("name", name);
        if (parent != null) {
            args.put("parent", parent);
        }
        CreateFolderTool tool = new CreateFolderTool(new AccountArgument(repo), leases,
                CreateFolderTool.angusDirectory(), commands);
        return tool.handle(new ToolCallContext(new AccountContext(tenant), "rid"), args);
    }

    @SuppressWarnings("unchecked")
    Map<String, Object> ok(String parent, String name) {
        ToolOutcome o = call(A, handleA(), parent, name);
        assertThat(o).isInstanceOf(ToolOutcome.Success.class);
        return (Map<String, Object>) ((ToolOutcome.Success) o).structured();
    }

    @SuppressWarnings("unchecked")
    List<Map<String, Object>> listed() {
        ToolOutcome o = new ListFoldersTool(new AccountArgument(repo), leases, 100, ListFoldersTool.CountEnricher.NONE)
                .handle(new ToolCallContext(new AccountContext(A), "rid"), Map.of("account", handleA()));
        return (List<Map<String, Object>>) ((Map<String, Object>) ((ToolOutcome.Success) o).structured()).get("folders");
    }

    List<String> listedNames() {
        return listed().stream().map(m -> (String) m.get("folder")).toList();
    }

    List<String> subscribed() throws Exception {
        var user = greenMail.getUserManager().getUserByEmail("alpha@localhost");
        return greenMail.getManagers().getImapHostManager().listSubscribedMailboxes(user, "*").stream()
                .map(f -> f.getFullName()).toList();
    }

    @Test
    void createsAndSubscribesATopLevelFolder() throws Exception {
        Map<String, Object> out = ok(null, "Projects");
        assertThat(out).containsEntry("folder", "Projects").containsEntry("created", true)
                .containsEntry("subscribed", true).containsEntry("parent", null).containsEntry("account", handleA());
        assertThat(out.get("delimiter")).isNotNull();
        assertThat(listedNames()).contains("Projects");
        assertThat(subscribed()).anyMatch(n -> n.endsWith(".Projects")); // GreenMail prefixes the user namespace
    }

    @Test
    void aRepeatCallIsCreatedFalseWithoutCreateOnTheWire() {
        ok(null, "Projects");
        commands.sent.clear();
        trace.list.clear();
        Map<String, Object> out = ok(null, "Projects");
        assertThat(out).containsEntry("created", false).containsEntry("subscribed", true);
        assertThat(commands.sent).containsExactly("SUBSCRIBE");
        assertThat(trace.list.stream().map(ILoggingEvent::getFormattedMessage).toList())
                .noneMatch(l -> l.matches("[A-Z]+\\d+ CREATE .*"));
    }

    @Test
    void aChildIsCreatedBelowItsParentWithTheServersDelimiter() {
        String delim = (String) ok(null, "Projects").get("delimiter");
        Map<String, Object> out = ok("Projects", "2026");
        assertThat(out).containsEntry("folder", "Projects" + delim + "2026").containsEntry("parent", "Projects")
                .containsEntry("created", true).containsEntry("delimiter", delim);
        assertThat(listedNames()).contains("Projects" + delim + "2026");
    }

    @Test
    void aMissingParentIsFolderNotFoundAndNothingIsCreated() {
        ToolOutcome o = call(A, handleA(), "Missing", "x");
        var e = ((ToolOutcome.Failure) o).error();
        assertThat(e.code()).isEqualTo(ErrorCode.FOLDER_NOT_FOUND);
        assertThat(e.details()).containsEntry("folder", "Missing");
        assertThat(commands.sent).isEmpty();
    }

    @Test
    void aNameWithTheDelimiterIsRejectedBeforeAnyCommand() {
        String delim = (String) ok(null, "Projects").get("delimiter");
        commands.sent.clear();
        var e = ((ToolOutcome.Failure) call(A, handleA(), null, "a" + delim + "b")).error();
        assertThat(e.code()).isEqualTo(ErrorCode.INVALID_ARGUMENT);
        assertThat(e.details()).containsEntry("reason", "contains_delimiter");
        assertThat(commands.sent).isEmpty();
    }

    @Test
    void inboxAtTheTopIsCreatedFalse() {
        Map<String, Object> out = ok(null, "inbox");
        assertThat(out).containsEntry("folder", "INBOX").containsEntry("created", false);
        assertThat(commands.sent).doesNotContain("CREATE");
    }

    @Test
    void aNonAsciiNameRoundTripsThroughModifiedUtf7() throws Exception {
        Map<String, Object> out = ok(null, "Entwürfe 日本");
        assertThat(out).containsEntry("folder", "Entwürfe 日本").containsEntry("created", true);
        assertThat(listedNames()).contains("Entwürfe 日本");
        assertThat(ok(null, "Entwürfe 日本")).containsEntry("created", false);
    }

    @Test
    void onlyListNamespaceCreateAndSubscribeAreSent() {
        ok(null, "Projects");
        List<String> lines = trace.list.stream().map(ILoggingEvent::getFormattedMessage).toList();
        assertThat(lines).anyMatch(l -> l.matches("[A-Z]+\\d+ CREATE .*"))
                .anyMatch(l -> l.matches("[A-Z]+\\d+ SUBSCRIBE .*"));
        assertThat(lines).noneMatch(l -> l.matches(
                "[A-Z]+\\d+ (SELECT|EXAMINE|STORE|EXPUNGE|FETCH|APPEND|COPY|DELETE|RENAME|UNSUBSCRIBE) .*"));
    }

    @Test
    void aForeignTenantsAccountHandleCreatesNothing() throws Exception {
        String foreign = repo.findByTenant(B).get(0).handle();
        var e = ((ToolOutcome.Failure) call(A, foreign, null, "Leak")).error();
        assertThat(e.code()).isEqualTo(ErrorCode.ACCOUNT_NOT_FOUND);
        assertThat(commands.sent).isEmpty();
        assertThat(listedNames()).doesNotContain("Leak");
        var beta = greenMail.getUserManager().getUserByEmail("beta@localhost");
        assertThat(greenMail.getManagers().getImapHostManager().getFolder(beta, "Leak")).isNull();
    }
}
