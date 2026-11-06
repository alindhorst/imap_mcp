package de.alexanderlindhorst.imapmcp.store.jdbc;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import javax.sql.DataSource;

public final class AuditRetention {

    public static final Duration RETENTION = Duration.ofDays(180);

    public AuditRetention(DataSource dataSource) {
    }

    public int deleteExpired(Instant now) {
        throw new UnsupportedOperationException();
    }

    public ScheduledFuture<?> schedule(ScheduledExecutorService executor) {
        throw new UnsupportedOperationException();
    }
}
