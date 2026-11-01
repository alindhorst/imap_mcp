package de.alexanderlindhorst.imapmcp.store.file;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import de.alexanderlindhorst.imapmcp.core.account.AccountHandles;
import de.alexanderlindhorst.imapmcp.core.account.AccountRepositoryContract;
import de.alexanderlindhorst.imapmcp.core.account.MailAccount;
import de.alexanderlindhorst.imapmcp.core.tenancy.AccountId;
import de.alexanderlindhorst.imapmcp.core.tenancy.TenantId;
import java.util.List;
import org.junit.jupiter.api.Test;

class ConfiguredAccountRepositoryTest {

    private static final byte[] SALT = new byte[16];
    private static final TenantId A = new TenantId("a");
    private static final TenantId B = new TenantId("b");
    private static final List<MailAccount> ACCOUNTS = List.of(account("work"), account("home"));

    private static MailAccount account(String id) {
        return new MailAccount(new AccountId(id), "L", null, "h", 993, MailAccount.Security.IMPLICIT_TLS,
                MailAccount.Flavor.GENERIC, "u", new de.alexanderlindhorst.imapmcp.core.account.SecretRef("env:X"));
    }

    @Test
    void handlesStableAcrossInstances() {
        String handle = AccountHandles.derive(SALT, A, new AccountId("work"));
        var first = new ConfiguredAccountRepository(A, ACCOUNTS, SALT);
        var second = new ConfiguredAccountRepository(A, ACCOUNTS, SALT.clone());
        assertThat(first.findByTenantAndHandle(A, handle)).isPresent();
        assertThat(second.findByTenantAndHandle(A, handle)).isPresent();
    }

    @Test
    void sameIdsUnderOtherOwnerAreNotCrossResolvable() {
        var repoA = new ConfiguredAccountRepository(A, ACCOUNTS, SALT);
        var repoB = new ConfiguredAccountRepository(B, ACCOUNTS, SALT);
        String handleA = AccountHandles.derive(SALT, A, new AccountId("work"));
        String handleB = AccountHandles.derive(SALT, B, new AccountId("work"));
        assertThat(repoB.findByTenantAndHandle(B, handleA)).isEmpty();
        assertThat(repoA.findByTenantAndHandle(A, handleB)).isEmpty();
        assertThat(repoA.findByTenantAndHandle(B, handleB)).isEmpty();
        assertThat(repoA.findByTenant(B)).isEmpty();
    }

    @Test
    void duplicateIdsRejectedNamingOnlyTheId() {
        MailAccount dup = new MailAccount(new AccountId("work"), "L", "SECRETADDR@x.org", "h", 1,
                MailAccount.Security.STARTTLS, MailAccount.Flavor.GMAIL, "SECRETUSER",
                new de.alexanderlindhorst.imapmcp.core.account.SecretRef("env:Y"));
        assertThatThrownBy(() -> new ConfiguredAccountRepository(A, List.of(account("work"), dup), SALT))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("work")
                .hasMessageNotContaining("SECRETADDR").hasMessageNotContaining("SECRETUSER");
    }

    @Test
    void accountListIsImmutableAfterConstruction() {
        var mutable = new java.util.ArrayList<>(ACCOUNTS);
        var repo = new ConfiguredAccountRepository(A, mutable, SALT);
        mutable.clear();
        assertThat(repo.findByTenant(A)).hasSize(2);
        assertThatThrownBy(() -> repo.findByTenant(A).clear()).isInstanceOf(UnsupportedOperationException.class);
    }
}
