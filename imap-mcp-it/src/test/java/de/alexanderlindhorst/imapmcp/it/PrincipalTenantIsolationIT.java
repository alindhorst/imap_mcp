package de.alexanderlindhorst.imapmcp.it;

import static org.assertj.core.api.Assertions.assertThat;

import de.alexanderlindhorst.imapmcp.core.account.CredentialStore;
import de.alexanderlindhorst.imapmcp.core.account.MailAccount;
import de.alexanderlindhorst.imapmcp.core.account.ResolvedAccount;
import de.alexanderlindhorst.imapmcp.core.crypto.KeyRing;
import de.alexanderlindhorst.imapmcp.core.crypto.LogIds;
import de.alexanderlindhorst.imapmcp.core.error.ErrorCode;
import de.alexanderlindhorst.imapmcp.core.handle.MessageHandles;
import de.alexanderlindhorst.imapmcp.core.handle.MessageRef;
import de.alexanderlindhorst.imapmcp.core.imap.CapabilityCache;
import de.alexanderlindhorst.imapmcp.core.imap.ImapSessionFactory;
import de.alexanderlindhorst.imapmcp.core.imap.ImapTimeouts;
import de.alexanderlindhorst.imapmcp.core.imap.pool.PoolSettings;
import de.alexanderlindhorst.imapmcp.core.imap.pool.StoreLeases;
import de.alexanderlindhorst.imapmcp.core.imap.pool.StorePool;
import de.alexanderlindhorst.imapmcp.core.mime.BodyWindowReader;
import de.alexanderlindhorst.imapmcp.core.tenancy.AccountContext;
import de.alexanderlindhorst.imapmcp.core.tenancy.TenantId;
import de.alexanderlindhorst.imapmcp.core.tool.ToolCallContext;
import de.alexanderlindhorst.imapmcp.core.tool.ToolOutcome;
import de.alexanderlindhorst.imapmcp.core.tools.AccountArgument;
import de.alexanderlindhorst.imapmcp.core.tools.GetMessageTool;
import de.alexanderlindhorst.imapmcp.core.tools.ListAccountsTool;
import de.alexanderlindhorst.imapmcp.core.tools.MessageLocator;
import de.alexanderlindhorst.imapmcp.store.jdbc.Database;
import de.alexanderlindhorst.imapmcp.store.jdbc.JdbcAccountRepository;
import de.alexanderlindhorst.imapmcp.store.jdbc.JdbcAccountRepository.NewAccount;
import de.alexanderlindhorst.imapmcp.store.jdbc.JdbcTenantDirectory;
import de.alexanderlindhorst.imapmcp.transport.http.PrincipalContextFilter;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * B2 on the real tenant directory and JDBC repository (SPEC 5.3, 5.8): two principals with their own tenant rows,
 * and a principal without a row, using the real tools. Account and message handles made for one tenant fail in
 * another without any IMAP connection or credential lookup (the credential store counts). Cursors need the folder
 * resolved on a connection first and are covered by TenantIsolationIT.
 */
class PrincipalTenantIsolationIT {

    private static final String ISSUER = "https://idp.example.com/realms/test";
    private static PostgresFixture postgres;
    private static Database db;
    private static JdbcTenantDirectory directory;
    private static JdbcAccountRepository repo;
    private static final AtomicInteger credentialLookups = new AtomicInteger();
    private static final KeyRing KEYS = new KeyRing(1, Map.of(1, filled(7)));
    private static final MessageHandles HANDLES = new MessageHandles(KEYS);
    private static StorePool pool;
    private static ImapSessionFactory sessions;
    private static ListAccountsTool listAccounts;
    private static GetMessageTool getMessage;
    private static TenantId tenantA;
    private static TenantId tenantB;
    private static ResolvedAccount accountA;
    private static ResolvedAccount accountB;

    private static byte[] filled(int v) {
        byte[] b = new byte[32];
        Arrays.fill(b, (byte) v);
        return b;
    }

    @BeforeAll
    static void start() {
        postgres = new PostgresFixture();
        postgres.start();
        db = Database.open(postgres.config());
        directory = new JdbcTenantDirectory(db.dataSource());
        repo = new JdbcAccountRepository(db.dataSource());
        tenantA = directory.create(ISSUER, "alice").tenantId();
        tenantB = directory.create(ISSUER, "bob").tenantId();
        accountA = repo.create(tenantA, account("alice-box"));
        accountB = repo.create(tenantB, account("bob-box"));
        sessions = new ImapSessionFactory(ImapTimeouts.DEFAULTS, p -> p);
        CredentialStore credentials = (t, a) -> {
            credentialLookups.incrementAndGet();
            return Optional.empty();
        };
        CapabilityCache capabilities = new CapabilityCache();
        pool = new StorePool(sessions, credentials, capabilities, key -> null, PoolSettings.DEFAULTS);
        StoreLeases leases = new StoreLeases(pool, Duration.ofSeconds(5));
        AccountArgument accounts = new AccountArgument(repo);
        listAccounts = new ListAccountsTool(repo, capabilities, leases, 2);
        getMessage = new GetMessageTool(accounts, new MessageLocator(HANDLES), leases,
                new BodyWindowReader(4L * 1024 * 1024), 20_000);
    }

    @AfterAll
    static void stop() {
        pool.close();
        sessions.close();
        db.close();
        postgres.stop();
    }

    private static NewAccount account(String label) {
        return new NewAccount(label, null, "127.0.0.1", 1, MailAccount.Security.IMPLICIT_TLS,
                MailAccount.Flavor.GENERIC, "u", true, false);
    }

    private static ToolCallContext ctx(TenantId t) {
        return new ToolCallContext(new AccountContext(t, java.util.Set.of("imap-mcp:read")), "rid");
    }

    private static ErrorCode failure(ToolOutcome o) {
        return ((ToolOutcome.Failure) o).error().code();
    }

    @Test
    void thePrincipalsMapToTheirOwnTenantRows() {
        assertThat(directory.find(ISSUER, "alice").orElseThrow().tenantId()).isEqualTo(tenantA);
        assertThat(directory.find(ISSUER, "bob").orElseThrow().tenantId()).isEqualTo(tenantB);
        assertThat(tenantA).isNotEqualTo(tenantB);
        assertThat(directory.find(ISSUER, "stranger")).isEmpty();
        assertThat(directory.find("https://other.example.com", "alice")).isEmpty();
    }

    @Test
    void aPrincipalWithoutARowSeesNoAccountsAndEveryAccountHandleIsUnknown() {
        TenantId none = PrincipalContextFilter.NO_TENANT;
        assertThat(repo.findByTenant(none)).isEmpty();
        assertThat(repo.findByTenantAndHandle(none, accountA.handle())).isEmpty();
        Map<String, Object> out = structured(listAccounts.handle(ctx(none), Map.of()));
        assertThat((List<?>) out.get("accounts")).isEmpty();
        int before = credentialLookups.get();
        assertThat(failure(getMessage.handle(ctx(none), Map.of("account", accountA.handle(), "message", "x"))))
                .isEqualTo(ErrorCode.ACCOUNT_NOT_FOUND);
        assertThat(credentialLookups.get()).isEqualTo(before);
    }

    @Test
    void anotherTenantsAccountHandleIsAccountNotFoundWithoutIo() {
        int before = credentialLookups.get();
        assertThat(failure(getMessage.handle(ctx(tenantB), Map.of("account", accountA.handle(), "message", "x"))))
                .isEqualTo(ErrorCode.ACCOUNT_NOT_FOUND);
        assertThat(credentialLookups.get()).isEqualTo(before);
    }

    @Test
    void aMessageHandleOfAnotherTenantIsInvalidHandleWithoutIo() {
        String ofAlice = HANDLES.encode(tenantA, accountA.account().id(), new MessageRef("INBOX", 1, 1));
        int before = credentialLookups.get();
        ToolOutcome out = getMessage.handle(ctx(tenantB), Map.of("account", accountB.handle(), "message", ofAlice));
        assertThat(failure(out)).isEqualTo(ErrorCode.INVALID_HANDLE);
        assertThat(credentialLookups.get()).isEqualTo(before);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> structured(ToolOutcome o) {
        return (Map<String, Object>) ((ToolOutcome.Success) o).structured();
    }
}
