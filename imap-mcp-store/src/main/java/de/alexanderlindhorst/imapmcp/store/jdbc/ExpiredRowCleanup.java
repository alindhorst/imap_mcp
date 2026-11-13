package de.alexanderlindhorst.imapmcp.store.jdbc;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import javax.sql.DataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Periodic removal of expired {@code web_session} and {@code oidc_pending} rows (SPEC 5.7). This is the one
 * deliberately tenant-independent statement of the store: it deletes by expiry time only and reads no tenant data.
 */
public final class ExpiredRowCleanup {

    private static final Logger LOG = LoggerFactory.getLogger(ExpiredRowCleanup.class);

    /** Number of rows removed per table. */
    public record Result(int webSessions, int oidcPending, int upstreamPending) {
    }

    private final DataSource dataSource;

    public ExpiredRowCleanup(DataSource dataSource) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
    }

    public Result deleteExpired(Instant now) {
        Timestamp cutoff = Timestamp.from(now);
        try (Connection c = dataSource.getConnection()) {
            return new Result(deleteFrom(c, "web_session", cutoff), deleteFrom(c, "oidc_pending", cutoff),
                    0); // stub: upstream_pending is not cleaned yet
        } catch (SQLException e) {
            throw new IllegalStateException("cleanup of expired rows failed", e);
        }
    }

    /** Runs the cleanup every {@code period}; a failed run is logged (no row data) and the next one still happens. */
    public ScheduledFuture<?> schedule(ScheduledExecutorService executor, Duration period) {
        return executor.scheduleWithFixedDelay(() -> {
            try {
                Result r = deleteExpired(Instant.now());
                LOG.debug("expired rows removed: web_session={}, oidc_pending={}", r.webSessions(), r.oidcPending());
            } catch (RuntimeException e) {
                LOG.warn("cleanup of expired web_session/oidc_pending rows failed", e);
            }
        }, period.toMillis(), period.toMillis(), TimeUnit.MILLISECONDS);
    }

    private static int deleteFrom(Connection c, String table, Timestamp cutoff) throws SQLException {
        // table is a constant of this class, never client input
        try (PreparedStatement ps = c.prepareStatement("DELETE FROM " + table + " WHERE expires_at <= ?")) {
            ps.setTimestamp(1, cutoff);
            return ps.executeUpdate();
        }
    }
}
