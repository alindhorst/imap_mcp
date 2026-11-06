package de.alexanderlindhorst.imapmcp.store.jdbc;

import de.alexanderlindhorst.imapmcp.core.web.OidcPendingStore;
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
import javax.sql.DataSource;

/**
 * {@link OidcPendingStore} on {@code oidc_pending} (SPEC 5.7): the key is the SHA-256 of the {@code state}; an
 * entry is removed by the statement that returns it, so it is used once even under concurrency. The table is
 * capped (counting unexpired rows only) because anyone can start a login.
 */
public final class JdbcOidcPendingStore implements OidcPendingStore {

    public static final int DEFAULT_MAX_PENDING = 10_000;

    private final DataSource dataSource;
    private final Clock clock;
    private final int maxPending;

    public JdbcOidcPendingStore(DataSource dataSource, Clock clock) {
        this(dataSource, clock, DEFAULT_MAX_PENDING);
    }

    public JdbcOidcPendingStore(DataSource dataSource, Clock clock, int maxPending) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
        this.clock = Objects.requireNonNull(clock, "clock");
        if (maxPending < 1) {
            throw new IllegalArgumentException("maxPending must be positive");
        }
        this.maxPending = maxPending;
    }

    @Override
    public String begin(String nonce, String pkceVerifier) {
        Objects.requireNonNull(nonce, "nonce");
        Objects.requireNonNull(pkceVerifier, "pkceVerifier");
        String state = WebTokens.encode(WebTokens.randomBytes());
        Instant now = clock.instant();
        try (Connection c = dataSource.getConnection();
                PreparedStatement ps = c.prepareStatement(
                        "INSERT INTO oidc_pending (state_hash, nonce, pkce_verifier, created_at, expires_at)"
                                + " SELECT ?, ?, ?, ?, ? WHERE (SELECT count(*) FROM oidc_pending WHERE expires_at > ?) < ?")) {
            ps.setBytes(1, WebTokens.sha256(state));
            ps.setString(2, nonce);
            ps.setString(3, pkceVerifier);
            ps.setObject(4, now.atOffset(ZoneOffset.UTC));
            ps.setObject(5, now.plus(TTL).atOffset(ZoneOffset.UTC));
            ps.setObject(6, now.atOffset(ZoneOffset.UTC));
            ps.setInt(7, maxPending);
            if (ps.executeUpdate() == 0) {
                throw new LimitReachedException();
            }
        } catch (SQLException e) {
            throw new IllegalStateException("pending login insert failed (sqlstate " + e.getSQLState() + ")");
        }
        return state;
    }

    @Override
    public Optional<Pending> consume(String state) {
        if (!WebTokens.wellFormed(state)) {
            return Optional.empty();
        }
        try (Connection c = dataSource.getConnection();
                PreparedStatement ps = c.prepareStatement(
                        "DELETE FROM oidc_pending WHERE state_hash = ? AND expires_at > ?"
                                + " RETURNING nonce, pkce_verifier")) {
            ps.setBytes(1, WebTokens.sha256(state));
            ps.setObject(2, clock.instant().atOffset(ZoneOffset.UTC));
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Optional.of(new Pending(rs.getString(1), rs.getString(2))) : Optional.empty();
            }
        } catch (SQLException e) {
            throw new IllegalStateException("pending login lookup failed (sqlstate " + e.getSQLState() + ")");
        }
    }
}
