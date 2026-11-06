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
 * Retention of {@code audit_event} (SPEC 5.3, D5): rows are kept 180 days and deleted daily. Like the other
 * cleanup it deletes by age only and reads no tenant data.
 */
public final class AuditRetention {

    public static final Duration RETENTION = Duration.ofDays(180);
    static final Duration PERIOD = Duration.ofDays(1);
    static final Duration FIRST_RUN = Duration.ofMinutes(10);

    private static final Logger LOG = LoggerFactory.getLogger(AuditRetention.class);

    private final DataSource dataSource;

    public AuditRetention(DataSource dataSource) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
    }

    /** @return the number of rows removed */
    public int deleteExpired(Instant now) {
        try (Connection c = dataSource.getConnection();
                PreparedStatement ps = c.prepareStatement("DELETE FROM audit_event WHERE occurred_at < ?")) {
            ps.setTimestamp(1, Timestamp.from(now.minus(RETENTION)));
            return ps.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("audit retention failed", e);
        }
    }

    /** First run 10 minutes after the call, then daily; a failed run is a WARN (no row data) and the next one still happens. */
    public ScheduledFuture<?> schedule(ScheduledExecutorService executor) {
        return executor.scheduleWithFixedDelay(() -> {
            try {
                int n = deleteExpired(Instant.now());
                LOG.info("audit retention removed {} rows", n);
            } catch (RuntimeException e) {
                LOG.warn("audit retention failed ({})", e.getClass().getSimpleName());
            }
        }, FIRST_RUN.toMillis(), PERIOD.toMillis(), TimeUnit.MILLISECONDS);
    }
}
