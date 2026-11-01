package de.alexanderlindhorst.imapmcp.core.metrics;

import de.alexanderlindhorst.imapmcp.core.crypto.LogIds;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

public final class InMemoryMetricsRecorder implements MetricsRecorder, AutoCloseable {

    public InMemoryMetricsRecorder(LogIds logIds, Clock clock) { }

    public void attachPool(PoolGauges gauges) { }

    public MetricsSnapshot snapshot() {
        return new MetricsSnapshot(0, 0, List.of(), List.of(), List.of());
    }

    @Override
    public void close() { }
}
