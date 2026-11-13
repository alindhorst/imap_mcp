package de.alexanderlindhorst.imapmcp.it;

import de.alexanderlindhorst.imapmcp.core.account.MailAccount;
import de.alexanderlindhorst.imapmcp.core.tenancy.TenantId;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.UUID;
import javax.sql.DataSource;

/** Direct SQL seeding for the Postgres ITs, independent of the code under test. */
final class AccountSql {

    private AccountSql() {
    }

    static void reset(DataSource ds) throws SQLException {
        try (Connection c = ds.getConnection(); Statement st = c.createStatement()) {
            st.execute("TRUNCATE tenant CASCADE");
        }
    }

    static void insertTenant(DataSource ds, TenantId tenant) throws SQLException {
        try (Connection c = ds.getConnection();
                PreparedStatement ps = c.prepareStatement(
                        "INSERT INTO tenant (id, issuer, subject) VALUES (?, 'https://idp.example', ?)")) {
            ps.setObject(1, UUID.fromString(tenant.value()));
            ps.setString(2, tenant.value());
            ps.executeUpdate();
        }
    }

    static TenantId newTenant(DataSource ds) throws SQLException {
        TenantId t = new TenantId(UUID.randomUUID().toString());
        insertTenant(ds, t);
        return t;
    }

    static void insertAccount(DataSource ds, TenantId tenant, MailAccount a, String handle) throws SQLException {
        try (Connection c = ds.getConnection();
                PreparedStatement ps = c.prepareStatement(
                        "INSERT INTO mailbox_account (tenant_id, id, handle, label, address, host, port, security,"
                                + " flavor, username, auth, read_only, allow_purge, provider_id) VALUES"
                                + " (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)")) {
            ps.setObject(1, UUID.fromString(tenant.value()));
            ps.setString(2, a.id().value());
            ps.setString(3, handle);
            ps.setString(4, a.label());
            ps.setString(5, a.address());
            ps.setString(6, a.host());
            ps.setInt(7, a.port());
            ps.setString(8, a.security().name());
            ps.setString(9, a.flavor().name());
            ps.setString(10, a.username());
            ps.setString(11, a.auth().name().toLowerCase(java.util.Locale.ROOT));
            ps.setBoolean(12, a.readOnly());
            ps.setBoolean(13, a.allowPurge());
            ps.setString(14, a.providerId());
            ps.executeUpdate();
        }
    }
}
