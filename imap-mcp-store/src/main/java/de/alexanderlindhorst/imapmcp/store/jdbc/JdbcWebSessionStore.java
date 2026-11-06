package de.alexanderlindhorst.imapmcp.store.jdbc;

import de.alexanderlindhorst.imapmcp.core.tenancy.TenantId;
import de.alexanderlindhorst.imapmcp.core.web.WebSession;
import de.alexanderlindhorst.imapmcp.core.web.WebSessionStore;
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
 * {@link WebSessionStore} on {@code web_session} (SPEC 5.7). The row is found by the SHA-256 of the session id, a
 * 256-bit secret, which is the one way into a tenant here; it joins {@code tenant} so a disabled tenant has no
 * session. {@code expires_at} is the current deadline: idle 30 minutes from the last use, capped at 8 hours after
 * {@code created_at}. The lookup and the extension are one statement.
 */
public final class JdbcWebSessionStore implements WebSessionStore {

    private static final String ABSOLUTE_SQL = "interval '" + ABSOLUTE_TIMEOUT.toHours() + " hours'";

    private final DataSource dataSource;
    private final Clock clock;

    public JdbcWebSessionStore(DataSource dataSource, Clock clock) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    @Override
    public Created create(TenantId tenant, Instant authTime) {
        UUID t = uuid(tenant);
        if (t == null) {
            throw new IllegalArgumentException("tenant id is not a UUID");
        }
        String sessionId = WebTokens.encode(WebTokens.randomBytes());
        byte[] csrf = WebTokens.randomBytes();
        Instant now = clock.instant();
        try (Connection c = dataSource.getConnection();
                PreparedStatement ps = c.prepareStatement(
                        "INSERT INTO web_session (id_hash, tenant_id, csrf_secret, auth_time, created_at, expires_at)"
                                + " VALUES (?, ?, ?, ?, ?, ?)")) {
            ps.setBytes(1, WebTokens.sha256(sessionId));
            ps.setObject(2, t);
            ps.setBytes(3, csrf);
            ps.setObject(4, utc(authTime));
            ps.setObject(5, utc(now));
            ps.setObject(6, utc(now.plus(IDLE_TIMEOUT)));
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("session insert failed (sqlstate " + e.getSQLState() + ")");
        }
        return new Created(sessionId, WebTokens.encode(csrf));
    }

    @Override
    public Optional<WebSession> find(String sessionId) {
        if (!WebTokens.wellFormed(sessionId)) {
            return Optional.empty();
        }
        Instant now = clock.instant();
        try (Connection c = dataSource.getConnection();
                PreparedStatement ps = c.prepareStatement(
                        "UPDATE web_session s SET expires_at = LEAST(?, s.created_at + " + ABSOLUTE_SQL + ")"
                                + " FROM tenant t WHERE s.id_hash = ? AND t.id = s.tenant_id AND t.status = 'active'"
                                + " AND s.expires_at > ? AND s.created_at + " + ABSOLUTE_SQL + " > ?"
                                + " RETURNING s.tenant_id, s.csrf_secret, s.auth_time, s.created_at")) {
            ps.setObject(1, utc(now.plus(IDLE_TIMEOUT)));
            ps.setBytes(2, WebTokens.sha256(sessionId));
            ps.setObject(3, utc(now));
            ps.setObject(4, utc(now));
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return Optional.empty();
                }
                return Optional.of(new WebSession(new TenantId(UUID.fromString(rs.getString(1)).toString()),
                        WebTokens.encode(rs.getBytes(2)), rs.getObject(3, OffsetDateTime.class).toInstant(),
                        rs.getObject(4, OffsetDateTime.class).toInstant()));
            }
        } catch (SQLException e) {
            throw new IllegalStateException("session lookup failed (sqlstate " + e.getSQLState() + ")");
        }
    }

    @Override
    public void delete(String sessionId) {
        if (!WebTokens.wellFormed(sessionId)) {
            return;
        }
        try (Connection c = dataSource.getConnection();
                PreparedStatement ps = c.prepareStatement("DELETE FROM web_session WHERE id_hash = ?")) {
            ps.setBytes(1, WebTokens.sha256(sessionId));
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("session delete failed (sqlstate " + e.getSQLState() + ")");
        }
    }

    private static OffsetDateTime utc(Instant i) {
        return i.atOffset(ZoneOffset.UTC);
    }

    private static UUID uuid(TenantId tenant) {
        try {
            UUID u = UUID.fromString(tenant.value());
            return u.toString().equals(tenant.value()) ? u : null;
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
