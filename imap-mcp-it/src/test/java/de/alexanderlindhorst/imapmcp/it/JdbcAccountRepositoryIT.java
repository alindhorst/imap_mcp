package de.alexanderlindhorst.imapmcp.it;

import static org.assertj.core.api.Assertions.assertThat;

import de.alexanderlindhorst.imapmcp.core.account.AccountHandles;
import de.alexanderlindhorst.imapmcp.core.account.MailAccount;
import de.alexanderlindhorst.imapmcp.core.account.ResolvedAccount;
import de.alexanderlindhorst.imapmcp.core.tenancy.AccountId;
import de.alexanderlindhorst.imapmcp.core.tenancy.TenantId;
import de.alexanderlindhorst.imapmcp.store.jdbc.Database;
import de.alexanderlindhorst.imapmcp.store.jdbc.JdbcAccountRepository;
import de.alexanderlindhorst.imapmcp.store.jdbc.JdbcAccountRepository.LoginState;
import de.alexanderlindhorst.imapmcp.store.jdbc.JdbcAccountRepository.NewAccount;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.regex.Pattern;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/** Account creation, field mapping and the failed-login counter on Postgres (A8). */
class JdbcAccountRepositoryIT {

    private static PostgresFixture postgres;
    private static Database db;
    private static JdbcAccountRepository repo;

    @BeforeAll
    static void start() {
        postgres = new PostgresFixture();
        postgres.start();
        db = Database.open(postgres.config());
        repo = new JdbcAccountRepository(db.dataSource());
    }

    @AfterAll
    static void stop() {
        if (db != null) {
            db.close();
        }
        postgres.stop();
    }

    private static NewAccount newAccount(String label) {
        return new NewAccount(label, "me@example.org", "imap.example.org", 993, MailAccount.Security.IMPLICIT_TLS,
                MailAccount.Flavor.GENERIC, "me", true, false);
    }

    @Test
    void createdAccountsGetRandomIdsAndHandlesInTheSpecifiedFormats() throws Exception {
        TenantId t = AccountSql.newTenant(db.dataSource());
        Set<String> ids = new HashSet<>();
        Set<String> handles = new HashSet<>();

        for (int i = 0; i < 20; i++) {
            ResolvedAccount r = repo.create(t, newAccount("l" + i));
            assertThat(r.account().id().value()).matches(Pattern.compile("[a-z2-7]{26}"));
            assertThat(AccountHandles.isValid(r.handle())).isTrue();
            ids.add(r.account().id().value());
            handles.add(r.handle());
        }

        assertThat(ids).hasSize(20);
        assertThat(handles).hasSize(20);
    }

    @Test
    void createdAccountIsMappedWithTheDbMarkerAndDefaults() throws Exception {
        TenantId t = AccountSql.newTenant(db.dataSource());
        ResolvedAccount created = repo.create(t, newAccount("Work"));

        MailAccount a = repo.findByTenantAndId(t, created.account().id()).orElseThrow().account();

        assertThat(a.secretRef().value()).isEqualTo("db:");
        assertThat(a.auth()).isEqualTo(MailAccount.Auth.PASSWORD);
        assertThat(a.status()).isEqualTo(MailAccount.Status.ACTIVE);
        assertThat(a.revision()).isEqualTo(1L);
        assertThat(a.readOnly()).isTrue();
        assertThat(a.allowPurge()).isFalse();
        assertThat(a.label()).isEqualTo("Work");
        assertThat(a.address()).isEqualTo("me@example.org");
        assertThat(a.host()).isEqualTo("imap.example.org");
        assertThat(a.port()).isEqualTo(993);
        assertThat(a.security()).isEqualTo(MailAccount.Security.IMPLICIT_TLS);
        assertThat(a.username()).isEqualTo("me");
    }

    @Test
    void anAccountWithoutAnAddressIsAllowed() throws Exception {
        TenantId t = AccountSql.newTenant(db.dataSource());
        ResolvedAccount created = repo.create(t, new NewAccount("l", null, "h", 143, MailAccount.Security.STARTTLS,
                MailAccount.Flavor.GMAIL, "u", false, true));

        MailAccount a = repo.findByTenantAndHandle(t, created.handle()).orElseThrow().account();

        assertThat(a.address()).isNull();
        assertThat(a.security()).isEqualTo(MailAccount.Security.STARTTLS);
        assertThat(a.flavor()).isEqualTo(MailAccount.Flavor.GMAIL);
        assertThat(a.readOnly()).isFalse();
        assertThat(a.allowPurge()).isTrue();
    }

    @Test
    void aForeignTenantNeverFindsTheAccountByHandleOrId() throws Exception {
        TenantId owner = AccountSql.newTenant(db.dataSource());
        TenantId other = AccountSql.newTenant(db.dataSource());
        ResolvedAccount created = repo.create(owner, newAccount("secret"));

        assertThat(repo.findByTenantAndHandle(other, created.handle())).isEmpty();
        assertThat(repo.findByTenantAndId(other, created.account().id())).isEmpty();
        assertThat(repo.findByTenant(other)).isEmpty();
        assertThat(repo.findByTenantAndHandle(new TenantId("not-a-uuid"), created.handle())).isEmpty();
        assertThat(repo.findByTenantAndId(new TenantId("not-a-uuid"), created.account().id())).isEmpty();
        assertThat(repo.findByTenant(new TenantId("not-a-uuid"))).isEmpty();
    }

    @Test
    void theThirdConsecutiveRefusedLoginMarksTheAccountNeedsRelinkAndRaisesTheRevision() throws Exception {
        TenantId t = AccountSql.newTenant(db.dataSource());
        ResolvedAccount created = repo.create(t, newAccount("l"));
        AccountId id = created.account().id();

        assertThat(repo.recordRefusedLogin(t, id)).isEqualTo(new LoginState(1, MailAccount.Status.ACTIVE));
        assertThat(repo.recordRefusedLogin(t, id)).isEqualTo(new LoginState(2, MailAccount.Status.ACTIVE));
        assertThat(repo.findByTenantAndId(t, id).orElseThrow().account().revision()).isEqualTo(1L);
        assertThat(repo.recordRefusedLogin(t, id)).isEqualTo(new LoginState(3, MailAccount.Status.NEEDS_RELINK));

        MailAccount after = repo.findByTenantAndId(t, id).orElseThrow().account();
        assertThat(after.status()).isEqualTo(MailAccount.Status.NEEDS_RELINK);
        assertThat(after.revision()).isEqualTo(2L); // pooled Stores of the old revision are obsolete
    }

    @Test
    void aSuccessfulLoginInBetweenResetsTheCounter() throws Exception {
        TenantId t = AccountSql.newTenant(db.dataSource());
        AccountId id = repo.create(t, newAccount("l")).account().id();

        repo.recordRefusedLogin(t, id);
        repo.recordRefusedLogin(t, id);
        repo.recordSuccessfulLogin(t, id);

        assertThat(repo.recordRefusedLogin(t, id)).isEqualTo(new LoginState(1, MailAccount.Status.ACTIVE));
    }

    @Test
    void concurrentRefusedLoginsAreCountedAtomicallyAndFlipTheStatusOnce() throws Exception {
        TenantId t = AccountSql.newTenant(db.dataSource());
        AccountId id = repo.create(t, newAccount("l")).account().id();

        try (ExecutorService pool = Executors.newFixedThreadPool(8)) {
            java.util.List<Future<LoginState>> results = new java.util.ArrayList<>();
            for (int i = 0; i < 40; i++) {
                results.add(pool.submit(() -> repo.recordRefusedLogin(t, id)));
            }
            Set<Integer> counts = new HashSet<>();
            for (Future<LoginState> f : results) {
                counts.add(f.get().failedLogins());
            }
            assertThat(counts).hasSize(40); // no lost update: every call saw its own count
        }

        MailAccount after = repo.findByTenantAndId(t, id).orElseThrow().account();
        assertThat(after.status()).isEqualTo(MailAccount.Status.NEEDS_RELINK);
        assertThat(after.revision()).isEqualTo(2L);
    }

    @Test
    void theCounterOfAnotherTenantsAccountIsNotTouched() throws Exception {
        TenantId owner = AccountSql.newTenant(db.dataSource());
        TenantId other = AccountSql.newTenant(db.dataSource());
        AccountId id = repo.create(owner, newAccount("l")).account().id();

        assertThat(repo.recordRefusedLogin(other, id)).isNull();
        assertThat(repo.recordRefusedLogin(owner, id)).isEqualTo(new LoginState(1, MailAccount.Status.ACTIVE));
    }
}
