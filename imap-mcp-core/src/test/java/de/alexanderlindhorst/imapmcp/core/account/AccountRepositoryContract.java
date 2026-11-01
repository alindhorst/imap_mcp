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

    protected static final TenantId TENANT_A = new TenantId("tenant-a");
    protected static final TenantId TENANT_B = new TenantId("tenant-b");

    private AccountRepository repository;

    protected abstract AccountRepository createRepository(Map<TenantId, List<MailAccount>> accountsByTenant);

    protected abstract String handleFor(AccountRepository repository, TenantId tenant, AccountId account);

    protected static MailAccount account(String id) {
        return new MailAccount(new AccountId(id), "Label " + id, null, "imap.example.org", 993,
                MailAccount.Security.IMPLICIT_TLS, MailAccount.Flavor.GENERIC, "user-" + id, new SecretRef("env:" + id));
    }

    @BeforeEach
    void setUpRepository() {
        // "shared" exists in both tenants on purpose: the same config id must not leak across tenants.
        repository = createRepository(Map.of(
                TENANT_A, List.of(account("shared"), account("only-a")),
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
        assertThat(repository.findByTenant(TENANT_B)).extracting(a -> a.id().value())
                .containsExactlyInAnyOrder("shared", "only-b");
        assertThat(repository.findByTenant(TENANT_A)).extracting(a -> a.id().value())
                .containsExactlyInAnyOrder("shared", "only-a");
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
}
