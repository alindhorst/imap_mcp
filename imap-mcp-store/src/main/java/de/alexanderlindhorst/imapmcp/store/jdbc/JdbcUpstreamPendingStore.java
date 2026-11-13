package de.alexanderlindhorst.imapmcp.store.jdbc;

import de.alexanderlindhorst.imapmcp.core.linking.UpstreamPendingStore;
import java.util.Objects;
import java.util.Optional;
import javax.sql.DataSource;
import java.time.Clock;

/** {@link UpstreamPendingStore} on {@code upstream_pending} (SPEC 5.3, 5.7). */
public final class JdbcUpstreamPendingStore implements UpstreamPendingStore {

    public static final int DEFAULT_MAX_PENDING = JdbcOidcPendingStore.DEFAULT_MAX_PENDING;

    public JdbcUpstreamPendingStore(DataSource dataSource, Clock clock) {
        this(dataSource, clock, DEFAULT_MAX_PENDING);
    }

    public JdbcUpstreamPendingStore(DataSource dataSource, Clock clock, int maxPending) {
        Objects.requireNonNull(dataSource, "dataSource");
    }

    @Override
    public String begin(Pending pending) {
        throw new UnsupportedOperationException("not yet");
    }

    @Override
    public Optional<Pending> consume(String state) {
        throw new UnsupportedOperationException("not yet");
    }
}
