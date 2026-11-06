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

        assertThat(repo.recordRefusedLogin(t, id, 1L)).isEqualTo(new LoginState(1, MailAccount.Status.ACTIVE));
        assertThat(repo.recordRefusedLogin(t, id, 1L)).isEqualTo(new LoginState(2, MailAccount.Status.ACTIVE));
        assertThat(repo.findByTenantAndId(t, id).orElseThrow().account().revision()).isEqualTo(1L);
        assertThat(repo.recordRefusedLogin(t, id, 1L)).isEqualTo(new LoginState(3, MailAccount.Status.NEEDS_RELINK));

        MailAccount after = repo.findByTenantAndId(t, id).orElseThrow().account();
        assertThat(after.status()).isEqualTo(MailAccount.Status.NEEDS_RELINK);
        assertThat(after.revision()).isEqualTo(2L); // pooled Stores of the old revision are obsolete
    }

    @Test
    void aSuccessfulLoginInBetweenResetsTheCounter() throws Exception {
        TenantId t = AccountSql.newTenant(db.dataSource());
        AccountId id = repo.create(t, newAccount("l")).account().id();

        repo.recordRefusedLogin(t, id, 1L);
        repo.recordRefusedLogin(t, id, 1L);
        repo.recordSuccessfulLogin(t, id, 1L);

        assertThat(repo.recordRefusedLogin(t, id, 1L)).isEqualTo(new LoginState(1, MailAccount.Status.ACTIVE));
    }

    @Test
    void concurrentRefusedLoginsAreCountedAtomicallyAndFlipTheStatusOnce() throws Exception {
        TenantId t = AccountSql.newTenant(db.dataSource());
        AccountId id = repo.create(t, newAccount("l")).account().id();

        Set<Integer> counts = new HashSet<>();
        int applied = 0;
        try (ExecutorService pool = Executors.newFixedThreadPool(8)) {
            java.util.List<Future<LoginState>> results = new java.util.ArrayList<>();
            for (int i = 0; i < 40; i++) {
                results.add(pool.submit(() -> repo.recordRefusedLogin(t, id, 1L)));
            }
            for (Future<LoginState> f : results) {
                LoginState state = f.get();
                if (state != null) {
                    applied++;
                    counts.add(state.failedLogins());
                }
            }
        }

        // revision 1 is only valid until the flip raises it: exactly the first three refusals are applied
        assertThat(applied).isEqualTo(3);
        assertThat(counts).containsExactlyInAnyOrder(1, 2, 3);
        MailAccount after = repo.findByTenantAndId(t, id).orElseThrow().account();
        assertThat(after.status()).isEqualTo(MailAccount.Status.NEEDS_RELINK);
        assertThat(after.revision()).isEqualTo(2L);
    }

    @Test
    void aStaleRevisionChangesNothing() throws Exception {
        TenantId t = AccountSql.newTenant(db.dataSource());
        AccountId id = repo.create(t, newAccount("l")).account().id();

        assertThat(repo.recordRefusedLogin(t, id, 0L)).isNull();
        assertThat(repo.recordRefusedLogin(t, id, 2L)).isNull();
        repo.recordSuccessfulLogin(t, id, 0L);
        assertThat(repo.recordRefusedLogin(t, id, 1L)).isEqualTo(new LoginState(1, MailAccount.Status.ACTIVE));
        repo.recordSuccessfulLogin(t, id, 7L); // stale: the counter stays
        assertThat(repo.recordRefusedLogin(t, id, 1L)).isEqualTo(new LoginState(2, MailAccount.Status.ACTIVE));
    }

    @Test
    void aRefusalAfterTheRelinkFlipIsIgnoredBecauseTheRevisionMovedOn() throws Exception {
        TenantId t = AccountSql.newTenant(db.dataSource());
        AccountId id = repo.create(t, newAccount("l")).account().id();
        for (int i = 0; i < 3; i++) {
            repo.recordRefusedLogin(t, id, 1L);
        }

        assertThat(repo.recordRefusedLogin(t, id, 1L)).isNull();
        assertThat(repo.findByTenantAndId(t, id).orElseThrow().account().revision()).isEqualTo(2L);
    }

    @Test
    void aSuccessfulLoginOfAForeignTenantLeavesTheOwnersCounterUnchanged() throws Exception {
        TenantId owner = AccountSql.newTenant(db.dataSource());
        TenantId other = AccountSql.newTenant(db.dataSource());
        AccountId id = repo.create(owner, newAccount("l")).account().id();
        repo.recordRefusedLogin(owner, id, 1L);

        repo.recordSuccessfulLogin(other, id, 1L);

        assertThat(repo.recordRefusedLogin(owner, id, 1L)).isEqualTo(new LoginState(2, MailAccount.Status.ACTIVE));
    }

    @Test
    void onlyTheCanonicalLowerCaseTenantUuidFindsAnything() throws Exception {
        TenantId t = AccountSql.newTenant(db.dataSource());
        ResolvedAccount created = repo.create(t, newAccount("l"));
        String canonical = t.value();
        for (String variant : new String[] {canonical.toUpperCase(), canonical.replace("-", ""),
                "{" + canonical + "}", " " + canonical, "1-1-1-1-1"}) {
            TenantId v = new TenantId(variant);
            assertThat(repo.findByTenant(v)).as(variant).isEmpty();
            assertThat(repo.findByTenantAndHandle(v, created.handle())).as(variant).isEmpty();
            assertThat(repo.findByTenantAndId(v, created.account().id())).as(variant).isEmpty();
            assertThat(repo.recordRefusedLogin(v, created.account().id(), 1L)).as(variant).isNull();
        }
        assertThat(repo.findByTenantAndId(t, created.account().id())).isPresent();
    }

    @Test
    void theCounterOfAnotherTenantsAccountIsNotTouched() throws Exception {
        TenantId owner = AccountSql.newTenant(db.dataSource());
        TenantId other = AccountSql.newTenant(db.dataSource());
        AccountId id = repo.create(owner, newAccount("l")).account().id();

        assertThat(repo.recordRefusedLogin(other, id, 1L)).isNull();
        assertThat(repo.recordRefusedLogin(owner, id, 1L)).isEqualTo(new LoginState(1, MailAccount.Status.ACTIVE));
    }
}
