package de.alexanderlindhorst.imapmcp.core.account;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import de.alexanderlindhorst.imapmcp.core.tenancy.AccountContext;
import de.alexanderlindhorst.imapmcp.core.tenancy.TenantId;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Arrays;
import org.junit.jupiter.api.Test;

class PoolKeyTest {

    private static final TenantId TENANT = new TenantId("t1");
    private static final ResolvedAccount RESOLVED = new ResolvedAccount(TENANT, AccountRepositoryContract.account("w"), "acc_test");

    @Test
    void derivedFromContextAndResolvedAccount() {
        PoolKey key = PoolKey.of(new AccountContext(TENANT), RESOLVED);
        assertThat(key.tenantId()).isEqualTo(TENANT);
        assertThat(key.accountId()).isEqualTo(RESOLVED.account().id());
        assertThat(key).isEqualTo(PoolKey.of(new AccountContext(TENANT), RESOLVED));
    }

    @Test
    void rejectsAccountOfAnotherTenant() {
        assertThatThrownBy(() -> PoolKey.of(new AccountContext(new TenantId("t2")), RESOLVED))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void notConstructibleFromClientStrings() {
        assertThat(PoolKey.class.getDeclaredConstructors()).allMatch(c -> !Modifier.isPublic(c.getModifiers()));
        for (Method m : PoolKey.class.getDeclaredMethods()) {
            if (Modifier.isPublic(m.getModifiers()) && Modifier.isStatic(m.getModifiers())) {
                assertThat(Arrays.asList(m.getParameterTypes())).as(m.getName())
                        .containsExactly(AccountContext.class, ResolvedAccount.class);
            }
        }
    }
}
