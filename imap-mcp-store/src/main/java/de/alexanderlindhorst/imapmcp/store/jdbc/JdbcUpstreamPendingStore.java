package de.alexanderlindhorst.imapmcp.store.jdbc;

import de.alexanderlindhorst.imapmcp.core.linking.UpstreamPendingStore;
import de.alexanderlindhorst.imapmcp.core.tenancy.AccountId;
import de.alexanderlindhorst.imapmcp.core.tenancy.TenantId;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import javax.sql.DataSource;

/**
 * {@link UpstreamPendingStore} on {@code upstream_pending} (SPEC 5.3, 5.7): the key is the SHA-256 of the
 * {@code state}; an entry is removed by the statement that returns it, so it is used once even under concurrency.
 * The caps count unexpired rows only: at most {@value UpstreamPendingStore#MAX_PER_TENANT} per tenant (serialized by
 * a lock on the tenant row, so concurrent starts cannot exceed it) and, as for {@code oidc_pending}, a table cap.
 */
public final class JdbcUpstreamPendingStore implements UpstreamPendingStore {

    public static final int DEFAULT_MAX_PENDING = JdbcOidcPendingStore.DEFAULT_MAX_PENDING;

    private final DataSource dataSource;
    private final Clock clock;
    private final int maxPending;

    public JdbcUpstreamPendingStore(DataSource dataSource, Clock clock) {
        this(dataSource, clock, DEFAULT_MAX_PENDING);
    }

    public JdbcUpstreamPendingStore(DataSource dataSource, Clock clock, int maxPending) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
        this.clock = Objects.requireNonNull(clock, "clock");
        if (maxPending < 1) {
            throw new IllegalArgumentException("maxPending must be positive");
        }
        this.maxPending = maxPending;
    }

    @Override
    public String begin(Pending p) {
        Objects.requireNonNull(p, "pending");
        UUID tenant = JdbcAccountRepository.uuid(p.tenant());
        if (tenant == null) {
            throw new IllegalArgumentException("tenant id is not a UUID");
        }
        String state = WebTokens.encode(WebTokens.randomBytes());
        OffsetDateTime now = clock.instant().atOffset(ZoneOffset.UTC);
        try (Connection c = dataSource.getConnection()) {
            c.setAutoCommit(false);
            try {
                try (PreparedStatement lock = c.prepareStatement("SELECT 1 FROM tenant WHERE id = ? FOR UPDATE")) {
                    lock.setObject(1, tenant);
                    try (ResultSet rs = lock.executeQuery()) {
                        if (!rs.next()) {
                            throw new SQLException("no such tenant", "02000");
                        }
                    }
                }
                if (count(c, "SELECT count(*) FROM upstream_pending WHERE tenant_id = ? AND expires_at > ?", tenant,
                        now) >= MAX_PER_TENANT) {
                    throw new TenantLimitReachedException();
                }
                if (count(c, "SELECT count(*) FROM upstream_pending WHERE expires_at > ?", now) >= maxPending) {
                    throw new LimitReachedException();
                }
                try (PreparedStatement ps = c.prepareStatement("INSERT INTO upstream_pending (state_hash, tenant_id,"
                        + " provider_id, pkce_verifier, label, address, read_only, relink_account_id, created_at,"
                        + " expires_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)")) {
                    ps.setBytes(1, WebTokens.sha256(state));
                    ps.setObject(2, tenant);
                    ps.setString(3, p.providerId());
                    ps.setString(4, p.pkceVerifier());
                    ps.setString(5, p.label());
                    ps.setString(6, p.address());
                    ps.setBoolean(7, p.readOnly());
                    ps.setString(8, p.relinkAccount() == null ? null : p.relinkAccount().value());
                    ps.setObject(9, now);
                    ps.setObject(10, now.plus(TTL));
                    ps.executeUpdate();
                }
                c.commit();
            } catch (SQLException | RuntimeException e) {
                c.rollback();
                throw e;
            }
        } catch (SQLException e) {
            throw new IllegalStateException("pending upstream login insert failed (sqlstate " + e.getSQLState() + ")");
        }
        return state;
    }

    private static int count(Connection c, String sql, Object... params) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            for (int i = 0; i < params.length; i++) {
                ps.setObject(i + 1, params[i]);
            }
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getInt(1);
            }
        }
    }

    @Override
    public Optional<Pending> consume(String state) {
        if (!WebTokens.wellFormed(state)) {
            return Optional.empty();
        }
        Instant now = clock.instant();
        try (Connection c = dataSource.getConnection();
                PreparedStatement ps = c.prepareStatement("DELETE FROM upstream_pending WHERE state_hash = ?"
                        + " AND expires_at > ? RETURNING tenant_id, provider_id, pkce_verifier, label, address,"
                        + " read_only, relink_account_id")) {
            ps.setBytes(1, WebTokens.sha256(state));
            ps.setObject(2, now.atOffset(ZoneOffset.UTC));
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return Optional.empty();
                }
                String relink = rs.getString(7);
                return Optional.of(new Pending(new TenantId(rs.getObject(1, UUID.class).toString()),
                        rs.getString(2), rs.getString(3), rs.getString(4), rs.getString(5), rs.getBoolean(6),
                        relink == null ? null : new AccountId(relink)));
            }
        } catch (SQLException e) {
            throw new IllegalStateException("pending upstream login lookup failed (sqlstate " + e.getSQLState() + ")");
        }
    }
}
