package de.alexanderlindhorst.imapmcp.core.account;

import static org.assertj.core.api.Assertions.assertThat;

import de.alexanderlindhorst.imapmcp.core.tenancy.AccountId;
import de.alexanderlindhorst.imapmcp.core.tenancy.TenantId;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Reusable tenant-isolation contract for {@link AccountRepository} implementations. Subclasses supply a
 * repository loaded with the given accounts and the client-facing handle the implementation derives for one.
 */
public abstract class AccountRepositoryContract {

    // UUID text so that a repository keyed by uuid (JDBC) can run the same contract.
    protected static final TenantId TENANT_A = new TenantId("00000000-0000-4000-8000-00000000000a");
    protected static final TenantId TENANT_B = new TenantId("00000000-0000-4000-8000-00000000000b");

    private AccountRepository repository;

    protected abstract AccountRepository createRepository(Map<TenantId, List<MailAccount>> accountsByTenant);

    protected abstract String handleFor(AccountRepository repository, TenantId tenant, AccountId account);

    protected static MailAccount account(String id) {
        return new MailAccount(new AccountId(id), "Label " + id, null, "imap.example.org", 993,
                MailAccount.Security.IMPLICIT_TLS, MailAccount.Flavor.GENERIC, "user-" + id, new SecretRef("env:" + id));
    }

    /** XOAUTH2 account (SPEC 5.7 v0.29): carries the provider id and an address, which the database requires. */
    protected static MailAccount oauthAccount(String id, String providerId) {
        return new MailAccount(new AccountId(id), "Label " + id, id + "@example.org", "imap.example.org", 993,
                MailAccount.Security.IMPLICIT_TLS, MailAccount.Flavor.GENERIC, id + "@example.org",
                new SecretRef("db:"), true, false, MailAccount.Auth.XOAUTH2, MailAccount.Status.ACTIVE, 0L,
                providerId);
    }

    @BeforeEach
    void setUpRepository() {
        // "shared" exists in both tenants on purpose: the same config id must not leak across tenants.
        repository = createRepository(Map.of(
                TENANT_A, List.of(account("shared"), account("only-a"), oauthAccount("oauth-a", "google")),
                TENANT_B, List.of(account("shared"), account("only-b"))));
    }

    @Test
    void ownHandleResolvesToOwnAccount() {
        String handle = handleFor(repository, TENANT_A, new AccountId("only-a"));
        ResolvedAccount resolved = repository.findByTenantAndHandle(TENANT_A, handle).orElseThrow();
        assertThat(resolved.tenantId()).isEqualTo(TENANT_A);
        assertThat(resolved.account().id()).isEqualTo(new AccountId("only-a"));
    }

    @Test
    void handleOfOtherTenantResolvesToNothing() {
        for (String id : List.of("shared", "only-a")) {
            String handleA = handleFor(repository, TENANT_A, new AccountId(id));
            assertThat(repository.findByTenantAndHandle(TENANT_B, handleA)).as(id).isEmpty();
        }
    }

    @Test
    void findByTenantNeverContainsOtherTenantsAccounts() {
        assertThat(repository.findByTenant(TENANT_B)).extracting(r -> r.account().id().value())
                .containsExactlyInAnyOrder("shared", "only-b");
        assertThat(repository.findByTenant(TENANT_A)).extracting(r -> r.account().id().value())
                .containsExactlyInAnyOrder("shared", "only-a", "oauth-a");
    }

    @Test
    void everyListedAccountResolvesByItsOwnHandleInItsTenant() {
        for (TenantId t : List.of(TENANT_A, TENANT_B)) {
            for (ResolvedAccount listed : repository.findByTenant(t)) {
                assertThat(listed.tenantId()).isEqualTo(t);
                assertThat(listed.handle()).isEqualTo(handleFor(repository, t, listed.account().id()));
                assertThat(repository.findByTenantAndHandle(t, listed.handle())).contains(listed);
            }
        }
    }

    @Test
    void listedHandlesDoNotResolveInTheOtherTenant() {
        for (ResolvedAccount listed : repository.findByTenant(TENANT_A)) {
            assertThat(repository.findByTenantAndHandle(TENANT_B, listed.handle())).isEmpty();
        }
    }

    @Test
    void unknownTenantSeesNothing() {
        assertThat(repository.findByTenant(new TenantId("nobody"))).isEmpty();
    }

    @Test
    void malformedHandlesReturnEmptyWithoutException() {
        for (String bad : new String[] {null, "", " ", "garbage", "acc_", "acc_%%%", "acc_" + "x".repeat(5000),
                "../../etc/passwd", "acc_\u0000"}) {
            assertThat(repository.findByTenantAndHandle(TENANT_A, bad)).as("handle %s", bad).isEmpty();
        }
    }

    @Test
    void findByTenantAndIdResolvesOwnAccountOnly() {
        ResolvedAccount own = repository.findByTenantAndId(TENANT_A, new AccountId("only-a")).orElseThrow();
        assertThat(own.tenantId()).isEqualTo(TENANT_A);
        assertThat(own.account().id()).isEqualTo(new AccountId("only-a"));
        assertThat(own.handle()).isEqualTo(handleFor(repository, TENANT_A, new AccountId("only-a")));
        assertThat(repository.findByTenantAndId(TENANT_B, new AccountId("only-a"))).isEmpty();
        assertThat(repository.findByTenantAndId(TENANT_A, new AccountId("only-b"))).isEmpty();
    }

    @Test
    void findByTenantAndIdOfSharedIdStaysInsideTheTenant() {
        for (TenantId t : List.of(TENANT_A, TENANT_B)) {
            ResolvedAccount r = repository.findByTenantAndId(t, new AccountId("shared")).orElseThrow();
            assertThat(r.tenantId()).isEqualTo(t);
            assertThat(r.handle()).isEqualTo(handleFor(repository, t, new AccountId("shared")));
        }
    }

    @Test
    void findByTenantAndIdOfUnknownTenantOrIdIsEmpty() {
        assertThat(repository.findByTenantAndId(new TenantId("nobody"), new AccountId("shared"))).isEmpty();
        assertThat(repository.findByTenantAndId(TENANT_A, new AccountId("missing"))).isEmpty();
    }

    @Test
    void providerIdRoundTripsThroughEveryFinder() {
        ResolvedAccount byId = repository.findByTenantAndId(TENANT_A, new AccountId("oauth-a")).orElseThrow();
        assertThat(byId.account().providerId()).isEqualTo("google");
        assertThat(byId.account().auth()).isEqualTo(MailAccount.Auth.XOAUTH2);
        assertThat(repository.findByTenantAndHandle(TENANT_A, byId.handle()).orElseThrow().account().providerId())
                .isEqualTo("google");
        assertThat(repository.findByTenant(TENANT_A)).filteredOn(r -> r.account().id().value().equals("oauth-a"))
                .singleElement().satisfies(r -> assertThat(r.account().providerId()).isEqualTo("google"));
    }

    @Test
    void passwordAccountsHaveNoProviderId() {
        assertThat(repository.findByTenantAndId(TENANT_A, new AccountId("only-a")).orElseThrow().account()
                .providerId()).isNull();
        assertThat(repository.findByTenantAndId(TENANT_B, new AccountId("shared")).orElseThrow().account()
                .providerId()).isNull();
    }
}
