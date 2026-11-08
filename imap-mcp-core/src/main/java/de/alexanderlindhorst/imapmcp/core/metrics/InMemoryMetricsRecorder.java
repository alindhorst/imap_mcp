package de.alexanderlindhorst.imapmcp.core.metrics;

import de.alexanderlindhorst.imapmcp.core.account.PoolKey;
import de.alexanderlindhorst.imapmcp.core.crypto.LogIds;
import de.alexanderlindhorst.imapmcp.core.metrics.MetricsSnapshot.OperationMetrics;
import de.alexanderlindhorst.imapmcp.core.metrics.MetricsSnapshot.PoolMetrics;
import de.alexanderlindhorst.imapmcp.core.metrics.MetricsSnapshot.TenantMetrics;
import de.alexanderlindhorst.imapmcp.core.tenancy.TenantId;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Internal in-memory metrics (SPEC 5.6, v1: no Micrometer, no endpoint). State is held per raw id internally and
 * exposed, in {@link #snapshot()} and in the periodic INFO summary, only as {@link LogIds} hashes. Pool gauges are
 * read from the attached pool at snapshot time. Counters are cumulative since construction; the summary repeats the
 * cumulative values and is emitted only when something was recorded since the previous one.
 */
public final class InMemoryMetricsRecorder implements MetricsRecorder, AutoCloseable {

    public static final Duration DEFAULT_SUMMARY_PERIOD = Duration.ofMinutes(5);

    private static final Logger LOG = LoggerFactory.getLogger(InMemoryMetricsRecorder.class);

    /** Upper bound of the series kept per kind (pool, tenant, operation); the rest is counted as overflow. */
    public static final int MAX_SERIES = 10_000;

    /** Series not updated for this many summary periods are dropped. */
    private static final int IDLE_PERIODS = 2;

    private record OpKey(TenantId tenant, String tool, String op) { }

    /** Time of the last update, for dropping idle series. */
    private abstract static class Touched {
        volatile Instant touched = Instant.EPOCH;
    }

    /** Count, sum and max of durations; tiny critical sections. */
    private static final class Timing extends Touched {
        private long count;
        private long sumNanos;
        private long maxNanos;

        synchronized void add(Duration d) {
            long n = Math.max(0, d.toNanos());
            count++;
            sumNanos += n;
            maxNanos = Math.max(maxNanos, n);
        }

        synchronized long count() {
            return count;
        }

        synchronized Duration avg() {
            return count == 0 ? Duration.ZERO : Duration.ofNanos(sumNanos / count);
        }

        synchronized Duration max() {
            return Duration.ofNanos(maxNanos);
        }
    }

    private static final class PoolStats extends Touched {
        final Timing wait = new Timing();
        final Map<String, AtomicLong> busy = new ConcurrentHashMap<>();
    }

    private static final class TenantStats extends Touched {
        final AtomicLong authFailures = new AtomicLong();
        final AtomicLong timeouts = new AtomicLong();
    }

    private final LogIds logIds;
    private final Clock clock;
    private final Map<PoolKey, PoolStats> pools = new ConcurrentHashMap<>();
    private final Map<TenantId, TenantStats> tenants = new ConcurrentHashMap<>();
    private final Map<OpKey, Timing> operations = new ConcurrentHashMap<>();
    private final AtomicLong events = new AtomicLong();
    private final AtomicLong overflow = new AtomicLong();
    private final int maxSeries;
    private volatile Duration summaryPeriod = DEFAULT_SUMMARY_PERIOD;
    private final Object summaryLock = new Object();
    private long eventsAtLastSummary; // guarded by summaryLock
    private volatile PoolGauges gauges;
    private ScheduledExecutorService scheduler; // guarded by this
    private ScheduledFuture<?> task; // guarded by this
    private boolean ownsScheduler; // guarded by this

    public InMemoryMetricsRecorder(LogIds logIds, Clock clock) {
        this(logIds, clock, MAX_SERIES);
    }

    InMemoryMetricsRecorder(LogIds logIds, Clock clock, int maxSeries) {
        this.maxSeries = maxSeries;
        this.logIds = Objects.requireNonNull(logIds, "logIds");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /** Source of the pool gauges (active, idle, created, destroyed); read at snapshot time. */
    public void attachPool(PoolGauges gauges) {
        this.gauges = Objects.requireNonNull(gauges, "gauges");
    }

    @Override
    public Instant now() {
        return clock.instant();
    }

    @Override
    public void leaseAcquired(PoolKey key, Duration wait) {
        PoolStats s = series(pools, key, PoolStats::new);
        if (s != null) {
            s.wait.add(wait);
        }
    }

    @Override
    public void operation(TenantId tenant, String tool, String op, Duration latency) {
        Timing t = series(operations, new OpKey(tenant, tool, op), Timing::new);
        if (t != null) {
            t.add(latency);
        }
    }

    @Override
    public void decryptFailure(TenantId tenant) {
        operation(tenant, "credentials", "decrypt_failure", Duration.ZERO);
    }

    @Override
    public void writeArgumentRejected(TenantId tenant, String tool) {
        operation(tenant, tool, "write_argument_rejected", Duration.ZERO);
    }

    @Override
    public void writeWithoutTenant(String tool) {
        operation(TenantId.NONE, tool, "write_without_tenant", Duration.ZERO); // replica-wide: no tenant
    }

    @Override
    public void keyServiceUnavailable(TenantId tenant) {
        operation(tenant, "credentials", "key_service_unavailable", Duration.ZERO);
    }

    @Override
    public void oauthKeysUnavailable() {
        operation(TenantId.NONE, "oauth", "keys_unavailable", Duration.ZERO); // replica-wide: no tenant
    }

    @Override
    public void oauthKeysStale() {
        operation(TenantId.NONE, "oauth", "keys_stale", Duration.ZERO); // replica-wide: no tenant
    }

    @Override
    public void initializeRateLimited(TenantId tenant) {
        operation(tenant, "initialize", "rate_limited", Duration.ZERO);
    }

    @Override
    public void authFailure(TenantId tenant) {
        TenantStats s = series(tenants, tenant, TenantStats::new);
        if (s != null) {
            s.authFailures.incrementAndGet();
        }
    }

    @Override
    public void busyRejection(PoolKey key, String scope) {
        PoolStats s = series(pools, key, PoolStats::new);
        if (s != null) {
            s.busy.computeIfAbsent(scope, x -> new AtomicLong()).incrementAndGet();
        }
    }

    @Override
    public void timeout(TenantId tenant) {
        TenantStats s = series(tenants, tenant, TenantStats::new);
        if (s != null) {
            s.timeouts.incrementAndGet();
        }
    }

    /**
     * The series for {@code key}, marked as updated now. At the cap, idle series are dropped first; if there is
     * still no room the event is counted in the single overflow series and null is returned (SPEC 5.5).
     */
    private <K, S extends Touched> S series(Map<K, S> map, K key, Supplier<S> factory) {
        events.incrementAndGet();
        S s = map.get(key);
        if (s == null) {
            if (map.size() >= maxSeries) {
                sweep();
                if (map.size() >= maxSeries) {
                    overflow.incrementAndGet();
                    return null;
                }
            }
            s = map.computeIfAbsent(key, k -> factory.get());
        }
        s.touched = clock.instant();
        return s;
    }

    /** Drops series not updated for two summary periods. */
    private void sweep() {
        Instant cutoff = clock.instant().minus(summaryPeriod.multipliedBy(IDLE_PERIODS));
        pools.values().removeIf(s -> s.touched.isBefore(cutoff));
        tenants.values().removeIf(s -> s.touched.isBefore(cutoff));
        operations.values().removeIf(s -> s.touched.isBefore(cutoff));
    }

    int seriesCount() {
        return pools.size() + tenants.size() + operations.size();
    }

    public MetricsSnapshot snapshot() {
        sweep();
        PoolGauges g = gauges;
        List<PoolMetrics> poolList = new ArrayList<>();
        pools.forEach((key, s) -> {
            Map<String, Long> busy = new TreeMap<>();
            s.busy.forEach((scope, n) -> busy.put(scope, n.get()));
            poolList.add(new PoolMetrics(logIds.tenant(key.tenantId().value()),
                    logIds.account(key.accountId().value()),
                    g == null ? 0 : g.numActive(key), g == null ? 0 : g.numIdle(key),
                    s.wait.count(), s.wait.avg(), s.wait.max(), busy));
        });
        List<TenantMetrics> tenantList = new ArrayList<>();
        tenants.forEach((t, s) -> tenantList.add(
                new TenantMetrics(logIds.tenant(t.value()), s.authFailures.get(), s.timeouts.get())));
        List<OperationMetrics> opList = new ArrayList<>();
        operations.forEach((k, t) -> opList.add(new OperationMetrics(logIds.tenant(k.tenant().value()),
                k.tool(), k.op(), t.count(), t.avg(), t.max())));
        poolList.sort(Comparator.comparing(PoolMetrics::tenant).thenComparing(PoolMetrics::account));
        tenantList.sort(Comparator.comparing(TenantMetrics::tenant));
        opList.sort(Comparator.comparing(OperationMetrics::tenant).thenComparing(OperationMetrics::tool)
                .thenComparing(OperationMetrics::op));
        return new MetricsSnapshot(g == null ? 0 : g.createdCount(), g == null ? 0 : g.destroyedCount(),
                poolList, tenantList, opList, overflow.get());
    }

    /**
     * Logs one INFO line with the cumulative values if anything was recorded since the previous call; otherwise
     * nothing. Only hashed ids appear. Public so tests (and shutdown) can trigger it without waiting.
     */
    public void logSummary() {
        synchronized (summaryLock) {
            long current = events.get();
            if (current == eventsAtLastSummary) {
                return;
            }
            eventsAtLastSummary = current;
        }
        MetricsSnapshot s = snapshot();
        StringBuilder sb = new StringBuilder("metrics summary created_total=").append(s.createdTotal())
                .append(" destroyed_total=").append(s.destroyedTotal())
                .append(" overflow=").append(s.overflowEvents());
        for (TenantMetrics t : s.tenants()) {
            sb.append(" | tenant=").append(t.tenant()).append(" auth_failures=").append(t.authFailures())
                    .append(" timeouts=").append(t.timeouts());
        }
        for (PoolMetrics p : s.pools()) {
            sb.append(" | pool tenant=").append(p.tenant()).append(" account=").append(p.account())
                    .append(" active=").append(p.active()).append(" idle=").append(p.idle())
                    .append(" leases=").append(p.leases()).append(" wait_avg_ms=").append(p.avgWait().toMillis())
                    .append(" wait_max_ms=").append(p.maxWait().toMillis())
                    .append(" busy=").append(p.busyRejections());
        }
        for (OperationMetrics o : s.operations()) {
            sb.append(" | op tenant=").append(o.tenant()).append(" tool=").append(o.tool())
                    .append(" op=").append(o.op()).append(" count=").append(o.count())
                    .append(" avg_ms=").append(o.avg().toMillis()).append(" max_ms=").append(o.max().toMillis());
        }
        LOG.info("{}", sb);
    }

    /** Starts the periodic summary on a private single virtual-thread scheduler, every 5 minutes. */
    public synchronized void start() {
        start(null, DEFAULT_SUMMARY_PERIOD);
    }

    /** Starts the periodic summary; {@code scheduler} null creates a private virtual-thread scheduler. */
    public synchronized void start(ScheduledExecutorService scheduler, Duration period) {
        Objects.requireNonNull(period, "period");
        if (task != null) {
            throw new IllegalStateException("already started");
        }
        if (scheduler == null) {
            ScheduledThreadPoolExecutor own = new ScheduledThreadPoolExecutor(1,
                    Thread.ofVirtual().name("metrics-summary").factory());
            own.setRemoveOnCancelPolicy(true);
            this.scheduler = own;
            this.ownsScheduler = true;
        } else {
            this.scheduler = scheduler;
            this.ownsScheduler = false;
        }
        this.summaryPeriod = period;
        long nanos = period.toNanos();
        task = this.scheduler.scheduleAtFixedRate(this::safeSummary, nanos, nanos, TimeUnit.NANOSECONDS);
    }

    private void safeSummary() {
        try {
            logSummary();
        } catch (RuntimeException e) {
            LOG.warn("metrics summary failed, cause={}", e.getClass().getName()); // never kill the schedule
        }
    }

    /** Stops the periodic summary (and a private scheduler); idempotent. */
    @Override
    public synchronized void close() {
        if (task != null) {
            task.cancel(false);
            task = null;
        }
        if (scheduler != null && ownsScheduler) {
            scheduler.shutdownNow();
        }
        scheduler = null;
    }
}
