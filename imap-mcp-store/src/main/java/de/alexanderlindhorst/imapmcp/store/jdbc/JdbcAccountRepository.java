package de.alexanderlindhorst.imapmcp.store.jdbc;

import de.alexanderlindhorst.imapmcp.core.account.AccountHandles;
import de.alexanderlindhorst.imapmcp.core.account.AccountRepository;
import de.alexanderlindhorst.imapmcp.core.account.MailAccount;
import de.alexanderlindhorst.imapmcp.core.account.ResolvedAccount;
import de.alexanderlindhorst.imapmcp.core.account.SecretRef;
import de.alexanderlindhorst.imapmcp.core.tenancy.AccountId;
import de.alexanderlindhorst.imapmcp.core.tenancy.TenantId;
import java.security.SecureRandom;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import javax.sql.DataSource;

/**
 * {@link AccountRepository} on {@code mailbox_account} (SPEC 5.7). Every statement filters on {@code tenant_id};
 * a tenant id that is not a UUID owns nothing. {@code secretRef} is the fixed marker {@code db:}: the credential
 * comes from {@link JdbcCredentialStore}, never from a reference resolver.
 */
public final class JdbcAccountRepository implements AccountRepository {

    /** Marker in {@code MailAccount.secretRef} for accounts whose credential lives in the {@code credential} table. */
    public static final SecretRef DB_SECRET_REF = new SecretRef("db:");

    /** Consecutive refused LOGINs after which an account needs a re-link (SPEC 5.3). */
    public static final int RELINK_AFTER_REFUSED_LOGINS = 3;

    /** Values of a new account; the repository chooses id and handle. */
    public record NewAccount(String label, String address, String host, int port, MailAccount.Security security,
            MailAccount.Flavor flavor, String username, boolean readOnly, boolean allowPurge) {

        public NewAccount {
            Objects.requireNonNull(label, "label");
            Objects.requireNonNull(host, "host");
            Objects.requireNonNull(security, "security");
            Objects.requireNonNull(flavor, "flavor");
            Objects.requireNonNull(username, "username");
        }

        @Override
        public String toString() {
            return "NewAccount[port=" + port + ", security=" + security + ", flavor=" + flavor + ", readOnly="
                    + readOnly + ", allowPurge=" + allowPurge + "]";
        }
    }

    /** Counter and status after a refused LOGIN was recorded. */
    public record LoginState(int failedLogins, MailAccount.Status status) {
    }

    private static final String COLUMNS = "id, handle, label, address, host, port, security, flavor, username, auth,"
            + " read_only, allow_purge, status, revision";
    private static final String BASE32 = "abcdefghijklmnopqrstuvwxyz234567";
    private static final int RANDOM_LENGTH = 26;
    private static final int CREATE_ATTEMPTS = 3;
    private static final String UNIQUE_VIOLATION = "23505";

    private final DataSource dataSource;
    private final SecureRandom random = new SecureRandom();

    public JdbcAccountRepository(DataSource dataSource) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
    }

    @Override
    public List<ResolvedAccount> findByTenant(TenantId tenant) {
        UUID t = uuid(tenant);
        if (t == null) {
            return List.of();
        }
        return query("SELECT " + COLUMNS + " FROM mailbox_account WHERE tenant_id = ? ORDER BY created_at, id", tenant,
                t, null);
    }

    @Override
    public Optional<ResolvedAccount> findByTenantAndHandle(TenantId tenant, String handle) {
        UUID t = uuid(tenant);
        if (t == null || !AccountHandles.isValid(handle)) {
            return Optional.empty();
        }
        return query("SELECT " + COLUMNS + " FROM mailbox_account WHERE tenant_id = ? AND handle = ?", tenant, t,
                handle).stream().findFirst();
    }

    @Override
    public Optional<ResolvedAccount> findByTenantAndId(TenantId tenant, AccountId id) {
        UUID t = uuid(tenant);
        if (t == null) {
            return Optional.empty();
        }
        return query("SELECT " + COLUMNS + " FROM mailbox_account WHERE tenant_id = ? AND id = ?", tenant, t,
                id.value()).stream().findFirst();
    }

    /**
     * Creates an active password account with random id and handle (26 random base32 characters each, the handle
     * prefixed {@code acc_}). The credential is stored separately ({@link JdbcCredentialStore#insert}).
     */
    public ResolvedAccount create(TenantId tenant, NewAccount a) {
        UUID t = uuid(tenant);
        if (t == null) {
            throw new IllegalArgumentException("tenant id is not a UUID");
        }
        for (int attempt = 1;; attempt++) {
            String id = randomBase32();
            String handle = "acc_" + randomBase32();
            try (Connection c = dataSource.getConnection();
                    PreparedStatement ps = c.prepareStatement(
                            "INSERT INTO mailbox_account (tenant_id, id, handle, label, address, host, port, security,"
                                    + " flavor, username, auth, read_only, allow_purge)"
                                    + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'password', ?, ?)")) {
                ps.setObject(1, t);
                ps.setString(2, id);
                ps.setString(3, handle);
                ps.setString(4, a.label());
                ps.setString(5, a.address());
                ps.setString(6, a.host());
                ps.setInt(7, a.port());
                ps.setString(8, a.security().name());
                ps.setString(9, a.flavor().name());
                ps.setString(10, a.username());
                ps.setBoolean(11, a.readOnly());
                ps.setBoolean(12, a.allowPurge());
                ps.executeUpdate();
            } catch (SQLException e) {
                if (UNIQUE_VIOLATION.equals(e.getSQLState()) && attempt < CREATE_ATTEMPTS) {
                    continue; // a random id or handle collided; draw again
                }
                throw failure("account insert failed", e);
            }
            return findByTenantAndId(tenant, new AccountId(id)).orElseThrow();
        }
    }

    /**
     * Records one LOGIN answered {@code NO} without {@code [UNAVAILABLE]} for the tenant's account. Atomic in SQL;
     * the third consecutive one sets {@code needs_relink} and raises the revision once, so pooled Stores of the old
     * revision are dropped. {@code revision} is the account revision the LOGIN used: a refusal for an older revision
     * (the credential or status changed meanwhile) changes nothing. Returns null when nothing matched (no such
     * account for the tenant, or a different revision).
     */
    public LoginState recordRefusedLogin(TenantId tenant, AccountId id, long revision) {
        UUID t = uuid(tenant);
        if (t == null) {
            return null;
        }
        String flips = "status = 'active' AND failed_logins + 1 >= " + RELINK_AFTER_REFUSED_LOGINS;
        try (Connection c = dataSource.getConnection();
                PreparedStatement ps = c.prepareStatement(
                        "UPDATE mailbox_account SET failed_logins = failed_logins + 1,"
                                + " status = CASE WHEN " + flips + " THEN 'needs_relink' ELSE status END,"
                                + " revision = CASE WHEN " + flips + " THEN revision + 1 ELSE revision END,"
                                + " updated_at = now() WHERE tenant_id = ? AND id = ? AND revision = ?"
                                + " RETURNING failed_logins, status")) {
            ps.setObject(1, t);
            ps.setString(2, id.value());
            ps.setLong(3, revision);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? new LoginState(rs.getInt(1), status(rs.getString(2))) : null;
            }
        } catch (SQLException e) {
            throw failure("login counter update failed", e);
        }
    }

    /**
     * A successful LOGIN ends the run of refusals; {@code revision} as for {@link #recordRefusedLogin}. Link probes
     * must not call this or {@code recordRefusedLogin}.
     */
    public void recordSuccessfulLogin(TenantId tenant, AccountId id, long revision) {
        UUID t = uuid(tenant);
        if (t == null) {
            return;
        }
        try (Connection c = dataSource.getConnection();
                PreparedStatement ps = c.prepareStatement(
                        "UPDATE mailbox_account SET failed_logins = 0, updated_at = now()"
                                + " WHERE tenant_id = ? AND id = ? AND revision = ? AND failed_logins <> 0")) {
            ps.setObject(1, t);
            ps.setString(2, id.value());
            ps.setLong(3, revision);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw failure("login counter reset failed", e);
        }
    }

    private List<ResolvedAccount> query(String sql, TenantId tenant, UUID tenantUuid, String param) {
        try (Connection c = dataSource.getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setObject(1, tenantUuid);
            if (param != null) {
                ps.setString(2, param);
            }
            List<ResolvedAccount> out = new ArrayList<>();
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.add(map(tenant, rs));
                }
            }
            return out;
        } catch (SQLException e) {
            throw failure("account lookup failed", e);
        }
    }

    private static ResolvedAccount map(TenantId tenant, ResultSet rs) throws SQLException {
        MailAccount account = new MailAccount(new AccountId(rs.getString(1)), rs.getString(3), rs.getString(4),
                rs.getString(5), rs.getInt(6), MailAccount.Security.valueOf(rs.getString(7)),
                MailAccount.Flavor.valueOf(rs.getString(8)), rs.getString(9), DB_SECRET_REF, rs.getBoolean(11),
                rs.getBoolean(12), auth(rs.getString(10)), status(rs.getString(13)), rs.getLong(14));
        return new ResolvedAccount(tenant, account, rs.getString(2));
    }

    private static MailAccount.Auth auth(String db) {
        return switch (db) {
            case "password" -> MailAccount.Auth.PASSWORD;
            case "xoauth2" -> MailAccount.Auth.XOAUTH2;
            default -> throw new IllegalStateException("unknown auth value in mailbox_account");
        };
    }

    private static MailAccount.Status status(String db) {
        return switch (db) {
            case "active" -> MailAccount.Status.ACTIVE;
            case "needs_relink" -> MailAccount.Status.NEEDS_RELINK;
            default -> throw new IllegalStateException("unknown status value in mailbox_account");
        };
    }

    private String randomBase32() {
        StringBuilder sb = new StringBuilder(RANDOM_LENGTH);
        for (int i = 0; i < RANDOM_LENGTH; i++) {
            sb.append(BASE32.charAt(random.nextInt(BASE32.length())));
        }
        return sb.toString();
    }

    /** Only the SQLState survives: driver messages can carry host names, SQL text or values. */
    private static IllegalStateException failure(String what, SQLException e) {
        return new IllegalStateException(what + " (sqlstate " + e.getSQLState() + ")");
    }

    private static UUID uuid(TenantId tenant) {
        try {
            UUID u = UUID.fromString(tenant.value());
            // UUID.fromString is lenient (upper case, short groups): only the canonical text owns anything
            return u.toString().equals(tenant.value()) ? u : null;
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
