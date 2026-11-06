package de.alexanderlindhorst.imapmcp.store.jdbc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import de.alexanderlindhorst.imapmcp.core.account.MailAccount;
import de.alexanderlindhorst.imapmcp.core.tenancy.AccountId;
import de.alexanderlindhorst.imapmcp.core.tenancy.TenantId;
import de.alexanderlindhorst.imapmcp.store.jdbc.JdbcAccountRepository.NewAccount;
import java.lang.reflect.Proxy;
import java.sql.SQLException;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;

class JdbcAccountRepositoryUnitTest {

    @Test
    void newAccountToStringHidesLabelAddressHostAndUsername() {
        NewAccount a = new NewAccount("Secret Label", "me@secret.example", "imap.secret.example", 993,
                MailAccount.Security.IMPLICIT_TLS, MailAccount.Flavor.GENERIC, "secret-user", true, false);

        assertThat(a.toString()).doesNotContain("Secret Label").doesNotContain("secret.example")
                .doesNotContain("secret-user").contains("993");
    }

    @Test
    void sqlFailuresKeepOnlyTheSqlStateAndNoCause() {
        DataSource broken = (DataSource) Proxy.newProxyInstance(getClass().getClassLoader(),
                new Class<?>[] {DataSource.class}, (proxy, method, args) -> {
                    throw new SQLException("boom host=db.secret.example password=hunter2", "08006");
                });
        JdbcAccountRepository repo = new JdbcAccountRepository(broken);
        TenantId t = new TenantId("00000000-0000-4000-8000-00000000000a");

        assertThatThrownBy(() -> repo.findByTenant(t)).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("08006").hasMessageNotContaining("secret").hasNoCause();
        assertThatThrownBy(() -> repo.recordRefusedLogin(t, new AccountId("a"), 1L))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("08006").hasNoCause();
    }
}
