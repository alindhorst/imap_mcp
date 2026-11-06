package de.alexanderlindhorst.imapmcp.core.metrics;

import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * Immutable point-in-time view of the in-memory metrics. All ids are hashed (LogIds). Counters are cumulative
 * since start; {@code active}/{@code idle} are gauges read at snapshot time. {@code createdTotal} and
 * {@code destroyedTotal} count Stores of the whole pool (Commons Pool keeps no per-key counters).
 * {@code overflowEvents} counts events of series that were refused because the series cap was reached (SPEC 5.5).
 */
public record MetricsSnapshot(long createdTotal, long destroyedTotal, List<PoolMetrics> pools,
        List<TenantMetrics> tenants, List<OperationMetrics> operations, long overflowEvents) {

    /** Without overflow (no series was refused). */
    public MetricsSnapshot(long createdTotal, long destroyedTotal, List<PoolMetrics> pools,
            List<TenantMetrics> tenants, List<OperationMetrics> operations) {
        this(createdTotal, destroyedTotal, pools, tenants, operations, 0L);
    }

    public MetricsSnapshot {
        pools = List.copyOf(pools);
        tenants = List.copyOf(tenants);
        operations = List.copyOf(operations);
    }

    /** Per (tenant, account): gauges, lease count, lease wait and admission refusals by scope. */
    public record PoolMetrics(String tenant, String account, int active, int idle, long leases, Duration avgWait,
            Duration maxWait, Map<String, Long> busyRejections) {
        public PoolMetrics {
            busyRejections = Map.copyOf(busyRejections);
        }
    }

    public record TenantMetrics(String tenant, long authFailures, long timeouts) { }

    public record OperationMetrics(String tenant, String tool, String op, long count, Duration avg, Duration max) { }
}
