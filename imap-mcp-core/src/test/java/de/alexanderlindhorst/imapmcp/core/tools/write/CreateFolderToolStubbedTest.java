package de.alexanderlindhorst.imapmcp.core.tools.write;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import de.alexanderlindhorst.imapmcp.core.account.InMemoryAccountRepository;
import de.alexanderlindhorst.imapmcp.core.account.MailAccount;
import de.alexanderlindhorst.imapmcp.core.account.MailAccount.Flavor;
import de.alexanderlindhorst.imapmcp.core.account.MailAccount.Security;
import de.alexanderlindhorst.imapmcp.core.account.SecretRef;
import de.alexanderlindhorst.imapmcp.core.error.ErrorCode;
import de.alexanderlindhorst.imapmcp.core.error.ToolError;
import de.alexanderlindhorst.imapmcp.core.imap.pool.ImapLease;
import de.alexanderlindhorst.imapmcp.core.imap.pool.StoreLeases;
import de.alexanderlindhorst.imapmcp.core.imap.pool.StorePool;
import de.alexanderlindhorst.imapmcp.core.tenancy.AccountContext;
import de.alexanderlindhorst.imapmcp.core.tenancy.AccountId;
import de.alexanderlindhorst.imapmcp.core.tenancy.TenantId;
import de.alexanderlindhorst.imapmcp.core.tool.ToolCallContext;
import de.alexanderlindhorst.imapmcp.core.tool.ToolOutcome;
import de.alexanderlindhorst.imapmcp.core.tools.AccountArgument;
import de.alexanderlindhorst.imapmcp.core.tools.write.CreateFolderTool.Directory;
import jakarta.mail.MessagingException;
import java.net.SocketTimeoutException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.eclipse.angus.mail.iap.Response;
import org.eclipse.angus.mail.imap.IMAPStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

/** create_folder with a scripted directory (LIST / NAMESPACE) and a scripted command class; no server. */
class CreateFolderToolStubbedTest {

    static final TenantId A = new TenantId("tenant-a");
    static final TenantId B = new TenantId("tenant-b");

    /** Scripted LIST / NAMESPACE; records the calls in order. */
    static class StubDirectory implements Directory {
        final Map<String, Entry> entries = new HashMap<>();
        Namespace namespace = new Namespace("", '.');
        final List<String> calls = new ArrayList<>();

        StubDirectory has(String full, String... attrs) {
            entries.put(full, new Entry(full, '.', List.of(attrs)));
            return this;
        }

        @Override
        public Namespace personalNamespace(ImapLease lease) {
            calls.add("NAMESPACE");
            return namespace;
        }

        @Override
        public Optional<Entry> list(ImapLease lease, String fullName) throws MessagingException {
            calls.add("LIST " + fullName);
            return Optional.ofNullable(entries.get(fullName));
        }
    }

    /** Scripted CREATE / SUBSCRIBE; checks the mutation mark at the moment of each command. */
    static final class StubCommands implements CreateFolderTool.Commands {
        final ToolCallContext ctx;
        final List<String> calls = new ArrayList<>();
        final List<Boolean> markedAtCall = new ArrayList<>();
        String createResponse = "A1 OK done";
        String subscribeResponse = "A2 OK done";
        MessagingException createThrows;
        MessagingException subscribeThrows;
        Runnable onCreateOk = () -> { };

        StubCommands(ToolCallContext ctx) {
            this.ctx = ctx;
        }

        static UidCommands.Result result(String line) {
            Response r = new Response(line);
            return new UidCommands.Result(r, new Response[] {r});
        }

        @Override
        public UidCommands.Result create(ImapLease lease, String name) throws MessagingException {
            calls.add("CREATE " + name);
            markedAtCall.add(ctx.mutationStarted());
            if (createThrows != null) {
                throw createThrows;
            }
            UidCommands.Result r = result(createResponse);
            if (r.ok()) {
                onCreateOk.run();
            }
            return r;
        }

        @Override
        public UidCommands.Result subscribe(ImapLease lease, String name) throws MessagingException {
            calls.add("SUBSCRIBE " + name);
            markedAtCall.add(ctx.mutationStarted());
            if (subscribeThrows != null) {
                throw subscribeThrows;
            }
            return result(subscribeResponse);
        }
    }

    StorePool pool;
    IMAPStore store;
    InMemoryAccountRepository repo;
    StoreLeases leases;
    StubDirectory dir;
    StubCommands cmds;
    ToolCallContext ctx;
    final ListAppender<ILoggingEvent> logs = new ListAppender<>();
    Logger logger;

    static MailAccount account(String id, boolean readOnly) {
        return new MailAccount(new AccountId(id), id, null, "127.0.0.1", 993, Security.IMPLICIT_TLS, Flavor.GENERIC,
                id, new SecretRef("env:" + id), readOnly);
    }

    @BeforeEach
    void setUp() throws Exception {
        pool = mock(StorePool.class);
        store = mock(IMAPStore.class);
        when(pool.borrow(any())).thenReturn(store);
        repo = new InMemoryAccountRepository(Map.of(A, List.of(account("rw", false), account("ro", true)),
                B, List.of(account("other", false))));
        leases = new StoreLeases(pool, Duration.ofSeconds(5));
        dir = new StubDirectory();
        ctx = new ToolCallContext(new AccountContext(A), "rid");
        cmds = new StubCommands(ctx);
        logger = (Logger) LoggerFactory.getLogger("de.alexanderlindhorst.imapmcp.core.tools.write");
        logger.setLevel(Level.INFO);
        logger.addAppender(logs);
        logs.start();
    }

    @AfterEach
    void tearDown() {
        logger.detachAppender(logs);
        logger.setLevel(null);
    }

    String handle(TenantId t, String id) {
        return repo.findByTenant(t).stream().filter(r -> r.account().id().value().equals(id)).findFirst().get()
                .handle();
    }

    ToolOutcome call(String parent, String name) {
        return call(handle(A, "rw"), parent, name);
    }

    ToolOutcome call(String account, String parent, String name) {
        Map<String, Object> args = new HashMap<>();
        args.put("account", account);
        args.put("name", name);
        if (parent != null) {
            args.put("parent", parent);
        }
        return new CreateFolderTool(new AccountArgument(repo), leases, dir, cmds).handle(ctx, args);
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> ok(ToolOutcome o) {
        assertThat(o).isInstanceOf(ToolOutcome.Success.class);
        return (Map<String, Object>) ((ToolOutcome.Success) o).structured();
    }

    static ToolError err(ToolOutcome o) {
        assertThat(o).isInstanceOf(ToolOutcome.Failure.class);
        return ((ToolOutcome.Failure) o).error();
    }

    // ---- parent resolution ----

    @Test
    void parentIsListedAndTheFullNameJoinsParentDelimiterAndName() {
        dir.has("Projects");
        Map<String, Object> out = ok(call("Projects", "2026"));
        assertThat(out).containsEntry("folder", "Projects.2026").containsEntry("parent", "Projects")
                .containsEntry("delimiter", ".").containsEntry("created", true).containsEntry("subscribed", true)
                .containsEntry("account", handle(A, "rw"));
        assertThat(cmds.calls).containsExactly("CREATE Projects.2026", "SUBSCRIBE Projects.2026");
    }

    @Test
    void missingOrNonExistentParentIsFolderNotFound() {
        ToolError e = err(call("Nope", "x"));
        assertThat(e.code()).isEqualTo(ErrorCode.FOLDER_NOT_FOUND);
        assertThat(e.details()).containsEntry("folder", "Nope");
        dir.has("Gone", "\\NonExistent");
        assertThat(err(call("Gone", "x")).code()).isEqualTo(ErrorCode.FOLDER_NOT_FOUND);
        assertThat(cmds.calls).isEmpty();
        assertThat(ctx.mutationStarted()).isFalse();
    }

    @Test
    void noInferiorsParentIsInvalidArgument() {
        dir.has("Leaf", "\\NoInferiors");
        ToolError e = err(call("Leaf", "x"));
        assertThat(e.code()).isEqualTo(ErrorCode.INVALID_ARGUMENT);
        assertThat(e.details()).containsEntry("field", "parent").containsEntry("reason", "no_inferiors");
        assertThat(cmds.calls).isEmpty();
    }

    @Test
    void noselectParentIsAllowed() {
        dir.has("Holder", "\\Noselect");
        assertThat(ok(call("Holder", "x"))).containsEntry("folder", "Holder.x").containsEntry("created", true);
    }

    @Test
    void parentOnAServerWithoutHierarchyIsNoHierarchy() {
        dir.entries.put("Flat", new Directory.Entry("Flat", null, List.of()));
        ToolError e = err(call("Flat", "x"));
        assertThat(e.code()).isEqualTo(ErrorCode.INVALID_ARGUMENT);
        assertThat(e.details()).containsEntry("field", "parent").containsEntry("reason", "no_hierarchy");
        assertThat(cmds.calls).isEmpty();
    }

    @Test
    void nameContainingTheDelimiterIsContainsDelimiter() {
        dir.has("Projects");
        ToolError e = err(call("Projects", "a.b"));
        assertThat(e.code()).isEqualTo(ErrorCode.INVALID_ARGUMENT);
        assertThat(e.details()).containsEntry("field", "name").containsEntry("reason", "contains_delimiter");
        dir.namespace = new Directory.Namespace("INBOX.", '.');
        assertThat(err(call(null, "a.b")).details()).containsEntry("reason", "contains_delimiter");
        assertThat(cmds.calls).isEmpty();
    }

    @Test
    void totalLengthOverOneThousandBytesIsOutOfRange() {
        String parent = "p".repeat(900);
        dir.has(parent);
        String name = "é".repeat(60); // 120 bytes: 900 + 1 + 120 = 1021
        ToolError e = err(call(parent, name));
        assertThat(e.code()).isEqualTo(ErrorCode.INVALID_ARGUMENT);
        assertThat(e.details()).containsEntry("field", "name").containsEntry("reason", "out_of_range")
                .containsEntry("max", 1000);
        assertThat(cmds.calls).isEmpty();
    }

    @Test
    void withoutParentThePersonalNamespacePrefixIsIncluded() {
        dir.namespace = new Directory.Namespace("INBOX.", '.');
        Map<String, Object> out = ok(call(null, "Projects"));
        assertThat(out).containsEntry("folder", "INBOX.Projects").containsEntry("parent", null)
                .containsEntry("delimiter", ".");
        assertThat(cmds.calls).containsExactly("CREATE INBOX.Projects", "SUBSCRIBE INBOX.Projects");
    }

    @Test
    void withoutHierarchyAndWithoutParentTheDelimiterIsNull() {
        dir.namespace = new Directory.Namespace("", null);
        assertThat(ok(call(null, "Flat"))).containsEntry("folder", "Flat").containsEntry("delimiter", null);
    }

    // ---- existing folders, INBOX ----

    @Test
    void anExistingSelectableFolderSendsNoCreateButStillSubscribes() {
        dir.has("Projects");
        Map<String, Object> out = ok(call(null, "Projects"));
        assertThat(out).containsEntry("created", false).containsEntry("subscribed", true);
        assertThat(cmds.calls).containsExactly("SUBSCRIBE Projects");
        assertThat(cmds.markedAtCall).containsExactly(true);
    }

    @Test
    void inboxAtTheTopIsCreatedFalseWithTheCanonicalName() {
        dir.has("INBOX");
        dir.entries.put("inbox", dir.entries.get("INBOX"));
        Map<String, Object> out = ok(call(null, "inbox"));
        assertThat(out).containsEntry("folder", "INBOX").containsEntry("created", false);
        assertThat(cmds.calls).containsExactly("SUBSCRIBE INBOX");
    }

    // ---- refused CREATE ----

    @Test
    void refusedCreateIsMappedByTheResponseCode() {
        cmds.createResponse = "A1 NO [NOPERM] nope";
        ToolError e = err(call(null, "X"));
        assertThat(e.code()).isEqualTo(ErrorCode.PERMISSION_DENIED);
        assertThat(e.details()).containsEntry("reason", "server_denied");
        cmds.createResponse = "A1 NO [OVERQUOTA] full";
        assertThat(err(call(null, "X")).code()).isEqualTo(ErrorCode.QUOTA_EXCEEDED);
        cmds.createResponse = "A1 NO [CANNOT] cannot";
        assertThat(err(call(null, "X")).code()).isEqualTo(ErrorCode.UPSTREAM_ERROR);
        cmds.createResponse = "A1 NO [UNAVAILABLE] later";
        assertThat(err(call(null, "X")).code()).isEqualTo(ErrorCode.UPSTREAM_UNAVAILABLE);
        cmds.createResponse = "A1 NO plain";
        assertThat(err(call(null, "X")).code()).isEqualTo(ErrorCode.UPSTREAM_ERROR);
        assertThat(cmds.calls).noneMatch(c -> c.startsWith("SUBSCRIBE"));
    }

    @Test
    void afterANoTheNameIsListedAgainAndAnExistingFolderIsCreatedFalse() {
        cmds.createResponse = "A1 NO [ALREADYEXISTS] exists";
        cmds.subscribeResponse = "A2 OK done";
        StubDirectory racing = new StubDirectory() {
            @Override
            public Optional<Entry> list(ImapLease lease, String fullName) throws MessagingException {
                Optional<Entry> r = super.list(lease, fullName);
                if (calls.stream().filter(c -> c.equals("LIST X")).count() >= 2) {
                    return Optional.of(new Entry("X", '.', List.of()));
                }
                return r;
            }
        };
        dir = racing;
        Map<String, Object> out = ok(call(null, "X"));
        assertThat(out).containsEntry("created", false).containsEntry("subscribed", true);
        assertThat(dir.calls.stream().filter(c -> c.equals("LIST X")).count()).isEqualTo(2);
        assertThat(cmds.calls).containsExactly("CREATE X", "SUBSCRIBE X");
    }

    @Test
    void alreadyExistsThatIsStillNotListedStaysAnError() {
        cmds.createResponse = "A1 NO [ALREADYEXISTS] exists";
        ToolError e = err(call(null, "X"));
        assertThat(e.code()).isEqualTo(ErrorCode.UPSTREAM_ERROR);
        assertThat(e.details()).containsEntry("response_code", "ALREADYEXISTS");
    }

    // ---- SUBSCRIBE ----

    @Test
    void subscribeNoIsNotFatal() throws Exception {
        cmds.subscribeResponse = "A2 NO [CANNOT] nope";
        Map<String, Object> out = ok(call(null, "X"));
        assertThat(out).containsEntry("created", true).containsEntry("subscribed", false);
        verify(pool, never()).invalidate(any(), any());
    }

    @Test
    void subscribeConnectionFailureIsNotFatalAndBreaksTheLease() throws Exception {
        cmds.subscribeThrows = new MessagingException("gone", new java.io.IOException("reset"));
        Map<String, Object> out = ok(call(null, "X"));
        assertThat(out).containsEntry("created", true).containsEntry("subscribed", false);
        verify(pool).invalidate(any(), any());
        verify(pool, never()).release(any(), any());
    }

    // ---- mutationStarted, timeouts ----

    @Test
    void theMutationMarkIsSetRightBeforeCreateNotBefore() {
        ok(call(null, "X"));
        assertThat(cmds.markedAtCall).containsExactly(true, true);
        assertThat(dir.calls).isNotEmpty();
    }

    @Test
    void listFailureBeforeTheMarkIsAPlainTimeout() {
        dir = new StubDirectory() {
            @Override
            public Optional<Entry> list(ImapLease lease, String fullName) throws MessagingException {
                throw new MessagingException("t", new SocketTimeoutException("read"));
            }
        };
        ToolError e = err(call(null, "X"));
        assertThat(e.code()).isEqualTo(ErrorCode.TIMEOUT);
        assertThat(e.details() == null || !e.details().containsKey("outcome")).isTrue();
        assertThat(ctx.mutationStarted()).isFalse();
    }

    @Test
    void timeoutAfterTheMarkCarriesOutcomeUnknown() throws Exception {
        cmds.createThrows = new MessagingException("t", new SocketTimeoutException("read"));
        ToolError e = err(call(null, "X"));
        assertThat(e.code()).isEqualTo(ErrorCode.TIMEOUT);
        assertThat(e.details()).containsEntry("outcome", "unknown");
        verify(pool).invalidate(any(), any());
    }

    // ---- read-only, tenancy ----

    @Test
    void readOnlyAccountIsPermissionDeniedWithZeroIo() {
        ToolError e = err(call(handle(A, "ro"), null, "X"));
        assertThat(e.code()).isEqualTo(ErrorCode.PERMISSION_DENIED);
        assertThat(e.details()).containsEntry("reason", "account_read_only");
        verifyNoInteractions(pool);
        assertThat(dir.calls).isEmpty();
        assertThat(cmds.calls).isEmpty();
    }

    @Test
    void foreignAndForgedAccountHandlesAreAccountNotFoundWithZeroIo() {
        ToolError foreign = err(call(handle(B, "other"), null, "X"));
        ToolError forged = err(call("acc_aaaaaaaaaaaaaaaaaaaaaaaaaa", null, "X"));
        assertThat(foreign.code()).isEqualTo(ErrorCode.ACCOUNT_NOT_FOUND);
        assertThat(foreign.toMap()).isEqualTo(forged.toMap());
        verifyNoInteractions(pool);
        assertThat(dir.calls).isEmpty();
        assertThat(cmds.calls).isEmpty();
    }

    // ---- logging ----

    @Test
    void oneLogLineWithCreatedAndSubscribedAndNoFolderName() {
        ok(call(null, "SecretFolderName"));
        assertThat(logs.list).hasSize(1);
        String line = logs.list.get(0).getFormattedMessage();
        assertThat(line).contains("tool=create_folder").contains("created=true").contains("subscribed=true")
                .doesNotContain("SecretFolderName");
    }

    @Test
    void aWholeCallErrorIsLoggedOnceWithTheCodeOnly() {
        cmds.createResponse = "A1 NO [NOPERM] SecretServerText";
        err(call(null, "SecretFolderName"));
        assertThat(logs.list).hasSize(1);
        assertThat(logs.list.get(0).getFormattedMessage()).contains("error_code=PERMISSION_DENIED")
                .doesNotContain("SecretFolderName").doesNotContain("SecretServerText");
    }
}
