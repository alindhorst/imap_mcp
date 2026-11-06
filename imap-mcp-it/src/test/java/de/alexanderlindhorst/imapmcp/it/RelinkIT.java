package de.alexanderlindhorst.imapmcp.it;

import static org.assertj.core.api.Assertions.assertThat;

import de.alexanderlindhorst.imapmcp.core.account.CredentialStore;
import de.alexanderlindhorst.imapmcp.core.account.MailAccount;
import de.alexanderlindhorst.imapmcp.core.account.ResolvedAccount;
import de.alexanderlindhorst.imapmcp.core.account.Secret;
import de.alexanderlindhorst.imapmcp.core.crypto.KeyRing;
import de.alexanderlindhorst.imapmcp.core.crypto.LogIds;
import de.alexanderlindhorst.imapmcp.core.error.ErrorCode;
import de.alexanderlindhorst.imapmcp.core.tool.ToolExecutor;
import de.alexanderlindhorst.imapmcp.core.tool.ToolHandler;
import de.alexanderlindhorst.imapmcp.core.imap.CapabilityCache;
import de.alexanderlindhorst.imapmcp.core.imap.ImapSessionFactory;
import de.alexanderlindhorst.imapmcp.core.imap.ImapTimeouts;
import de.alexanderlindhorst.imapmcp.core.imap.pool.PoolSettings;
import de.alexanderlindhorst.imapmcp.core.imap.pool.StoreLeases;
import de.alexanderlindhorst.imapmcp.core.imap.pool.StorePool;
import de.alexanderlindhorst.imapmcp.core.metrics.MetricsRecorder;
import de.alexanderlindhorst.imapmcp.core.tenancy.AccountContext;
import de.alexanderlindhorst.imapmcp.core.tenancy.TenantId;
import de.alexanderlindhorst.imapmcp.core.tool.ToolCallContext;
import de.alexanderlindhorst.imapmcp.core.tool.ToolOutcome;
import de.alexanderlindhorst.imapmcp.core.tools.AccountArgument;
import de.alexanderlindhorst.imapmcp.core.tools.ListAccountsTool;
import de.alexanderlindhorst.imapmcp.core.tools.ListFoldersTool;
import de.alexanderlindhorst.imapmcp.store.jdbc.Database;
import de.alexanderlindhorst.imapmcp.store.jdbc.JdbcAccountRepository;
import de.alexanderlindhorst.imapmcp.store.jdbc.JdbcAccountRepository.NewAccount;
import de.alexanderlindhorst.imapmcp.store.jdbc.JdbcLoginOutcomes;
import de.alexanderlindhorst.imapmcp.store.jdbc.JdbcTenantDirectory;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * B4 end to end (SPEC 5.3 "Re-link"): real Postgres rows, a real pool and a GreenMail server. Three consecutive
 * refused logins flip the account to needs_relink; from then on every account tool fails with relink_required
 * before any credential lookup or connection, and list_accounts reports it without a probe.
 */
class RelinkIT {

    private static final String LINK_URL = "https://mail.example.com/accounts";
    private static PostgresFixture postgres;
    private static Database db;
    private static GreenMailFixture mail;
    private static JdbcAccountRepository repo;
    private static JdbcTenantDirectory directory;
    private static StorePool pool;
    private static ImapSessionFactory sessions;
    private static ToolHandler listFolders;
    private static ListAccountsTool listAccounts;
    private static final AtomicInteger credentialLookups = new AtomicInteger();
    private static final AtomicReference<String> password = new AtomicReference<>("wrong");
    private static int n;

    @BeforeAll
    static void start() {
        postgres = new PostgresFixture();
        postgres.start();
        db = Database.open(postgres.config());
        mail = GreenMailFixture.start();
        mail.createUser("u@localhost", "u", "right");
        repo = new JdbcAccountRepository(db.dataSource());
        directory = new JdbcTenantDirectory(db.dataSource());
        sessions = new ImapSessionFactory(ImapTimeouts.DEFAULTS, p -> {
            p.put("mail.imaps.ssl.trust", "*");
            p.put("mail.imaps.ssl.checkserveridentity", "false");
            return p;
        }, true);
        CredentialStore credentials = (t, a) -> {
            credentialLookups.incrementAndGet();
            return Optional.of(new Secret(password.get().toCharArray()));
        };
        CapabilityCache capabilities = new CapabilityCache();
        pool = new StorePool(sessions, credentials, capabilities, repo, PoolSettings.DEFAULTS, MetricsRecorder.NOOP,
                new JdbcLoginOutcomes(repo));
        StoreLeases leases = new StoreLeases(pool, Duration.ofSeconds(10));
        listFolders = new ToolExecutor(Duration.ofSeconds(20),
                new LogIds(new KeyRing(1, Map.of(1, new byte[32])))).wrap(ListFoldersTool.DEFINITION,
                        new ListFoldersTool(new AccountArgument(repo, LINK_URL), leases, 100));
        listAccounts = new ListAccountsTool(repo, capabilities, leases, 2, LINK_URL);
    }

    @AfterAll
    static void stop() {
        pool.close();
        sessions.close();
        mail.close();
        db.close();
        postgres.stop();
    }

    private TenantId newTenant() {
        return directory.create("https://idp.example.com/realms/test", "user-" + (++n)).tenantId();
    }

    private ResolvedAccount newAccount(TenantId t) {
        return repo.create(t, new NewAccount("box", null, GreenMailFixture.HOST, mail.imapsPort(),
                MailAccount.Security.IMPLICIT_TLS, MailAccount.Flavor.GENERIC, "u", true, false));
    }

    private ErrorCode call(TenantId t, ResolvedAccount a) {
        ToolOutcome o = listFolders.handle(new ToolCallContext(new AccountContext(t), "rid"),
                Map.of("account", a.handle()));
        return o instanceof ToolOutcome.Failure f ? f.error().code() : null;
    }

    private MailAccount reload(TenantId t, ResolvedAccount a) {
        return repo.findByTenantAndId(t, a.account().id()).orElseThrow().account();
    }

    @Test
    void threeConsecutiveRefusedLoginsFlipTheAccountAndThenNothingIsAttempted() {
        password.set("wrong");
        TenantId t = newTenant();
        ResolvedAccount a = newAccount(t);
        long revision = reload(t, a).revision();
        for (int i = 0; i < 3; i++) {
            assertThat(call(t, a)).isEqualTo(ErrorCode.AUTH_FAILED);
        }
        MailAccount flipped = reload(t, a);
        assertThat(flipped.status()).isEqualTo(MailAccount.Status.NEEDS_RELINK);
        assertThat(flipped.revision()).isEqualTo(revision + 1);

        int lookups = credentialLookups.get();
        ToolOutcome o = listFolders.handle(new ToolCallContext(new AccountContext(t), "rid-4"),
                Map.of("account", a.handle()));
        var error = ((ToolOutcome.Failure) o).error();
        assertThat(error.code()).isEqualTo(ErrorCode.AUTH_FAILED);
        assertThat(error.details()).containsEntry("reason", "relink_required").containsEntry("link_url", LINK_URL);
        assertThat(credentialLookups.get()).isEqualTo(lookups); // zero I/O: no credential lookup, no connect
    }

    @Test
    void aSuccessfulLoginEndsTheRunOfRefusals() {
        TenantId t = newTenant();
        ResolvedAccount a = newAccount(t);
        password.set("wrong");
        assertThat(call(t, a)).isEqualTo(ErrorCode.AUTH_FAILED);
        assertThat(call(t, a)).isEqualTo(ErrorCode.AUTH_FAILED);
        assertThat(failedLogins(t, a)).isEqualTo(2);
        password.set("right");
        assertThat(call(t, a)).isNull();
        assertThat(failedLogins(t, a)).isZero();
        assertThat(reload(t, a).status()).isEqualTo(MailAccount.Status.ACTIVE);
    }

    private int failedLogins(TenantId t, ResolvedAccount a) {
        try (var c = db.dataSource().getConnection();
                var ps = c.prepareStatement("SELECT failed_logins FROM mailbox_account WHERE tenant_id = ?::uuid"
                        + " AND id = ?")) {
            ps.setString(1, t.value());
            ps.setString(2, a.account().id().value());
            try (var rs = ps.executeQuery()) {
                rs.next();
                return rs.getInt(1);
            }
        } catch (java.sql.SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    @Test
    void listAccountsReportsNeedsRelinkWithoutAProbeAndTheLinkUrl() {
        password.set("wrong");
        TenantId t = newTenant();
        ResolvedAccount a = newAccount(t);
        for (int i = 0; i < 3; i++) {
            call(t, a);
        }
        int lookups = credentialLookups.get();
        @SuppressWarnings("unchecked")
        Map<String, Object> out = (Map<String, Object>) ((ToolOutcome.Success) listAccounts.handle(
                new ToolCallContext(new AccountContext(t), "rid"), Map.of())).structured();
        assertThat(out).containsEntry("link_url", LINK_URL);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> accounts = (List<Map<String, Object>>) out.get("accounts");
        assertThat(accounts).singleElement().satisfies(m -> assertThat(m).containsEntry("status", "needs_relink"));
        assertThat(credentialLookups.get()).isEqualTo(lookups);
    }

    @Test
    void anotherTenantsAccountIsNeverAffectedByTheCounter() {
        password.set("wrong");
        TenantId t1 = newTenant();
        TenantId t2 = newTenant();
        ResolvedAccount a1 = newAccount(t1);
        ResolvedAccount a2 = newAccount(t2);
        for (int i = 0; i < 3; i++) {
            call(t1, a1);
        }
        assertThat(reload(t1, a1).status()).isEqualTo(MailAccount.Status.NEEDS_RELINK);
        assertThat(reload(t2, a2).status()).isEqualTo(MailAccount.Status.ACTIVE);
        assertThat(call(t2, a2)).isEqualTo(ErrorCode.AUTH_FAILED); // still tries, with its own counter
    }
}
