package de.alexanderlindhorst.imapmcp.it;

import de.alexanderlindhorst.imapmcp.core.account.AccountRepository;
import de.alexanderlindhorst.imapmcp.core.account.AccountRepositoryContract;
import de.alexanderlindhorst.imapmcp.core.account.MailAccount;
import de.alexanderlindhorst.imapmcp.core.tenancy.AccountId;
import de.alexanderlindhorst.imapmcp.core.tenancy.TenantId;
import de.alexanderlindhorst.imapmcp.store.jdbc.Database;
import de.alexanderlindhorst.imapmcp.store.jdbc.JdbcAccountRepository;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;

/** The JDBC repository passes the core tenant-isolation contract (A8, SPEC 5.7). */
class JdbcAccountRepositoryContractIT extends AccountRepositoryContract {

    private static PostgresFixture postgres;
    private static Database db;

    @BeforeAll
    static void start() {
        postgres = new PostgresFixture();
        postgres.start();
        db = Database.open(postgres.config());
    }

    @AfterAll
    static void stop() {
        if (db != null) {
            db.close();
        }
        postgres.stop();
    }

    @Override
    protected AccountRepository createRepository(Map<TenantId, List<MailAccount>> accountsByTenant) {
        try {
            AccountSql.reset(db.dataSource());
            for (Map.Entry<TenantId, List<MailAccount>> e : accountsByTenant.entrySet()) {
                AccountSql.insertTenant(db.dataSource(), e.getKey());
                for (MailAccount a : e.getValue()) {
                    AccountSql.insertAccount(db.dataSource(), e.getKey(), a, "acc_" + randomHandleTail());
                }
            }
        } catch (SQLException ex) {
            throw new IllegalStateException(ex);
        }
        return new JdbcAccountRepository(db.dataSource());
    }

    @Override
    protected String handleFor(AccountRepository repository, TenantId tenant, AccountId account) {
        try (Connection c = db.dataSource().getConnection();
                PreparedStatement ps = c.prepareStatement(
                        "SELECT handle FROM mailbox_account WHERE tenant_id = ? AND id = ?")) {
            ps.setObject(1, UUID.fromString(tenant.value()));
            ps.setString(2, account.value());
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getString(1) : null;
            }
        } catch (SQLException ex) {
            throw new IllegalStateException(ex);
        }
    }

    private static String randomHandleTail() {
        String alphabet = "abcdefghijklmnopqrstuvwxyz234567";
        java.util.Random r = new java.security.SecureRandom();
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 26; i++) {
            sb.append(alphabet.charAt(r.nextInt(32)));
        }
        return sb.toString();
    }
}
