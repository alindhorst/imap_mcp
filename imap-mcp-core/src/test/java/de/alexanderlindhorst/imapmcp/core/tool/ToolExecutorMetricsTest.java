package de.alexanderlindhorst.imapmcp.core.tool;

import static org.assertj.core.api.Assertions.assertThat;

import de.alexanderlindhorst.imapmcp.core.crypto.KeyRing;
import de.alexanderlindhorst.imapmcp.core.crypto.LogIds;
import de.alexanderlindhorst.imapmcp.core.error.ServerBusyException;
import de.alexanderlindhorst.imapmcp.core.error.ToolError;
import de.alexanderlindhorst.imapmcp.core.metrics.InMemoryMetricsRecorder;
import de.alexanderlindhorst.imapmcp.core.metrics.MetricsSnapshot;
import de.alexanderlindhorst.imapmcp.core.tenancy.AccountContext;
import de.alexanderlindhorst.imapmcp.core.tenancy.TenantId;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import jakarta.mail.AuthenticationFailedException;
import org.junit.jupiter.api.Test;

class ToolExecutorMetricsTest {

    static final TenantId T1 = new TenantId("tenant-one");
    static final TenantId T2 = new TenantId("tenant-two");

    final LogIds logIds = new LogIds(new KeyRing(1, Map.of(1, new byte[32])));
    final AtomicReference<Instant> time = new AtomicReference<>(Instant.parse("2026-01-01T00:00:00Z"));
    final Clock clock = new Clock() {
        public ZoneId getZone() { return ZoneOffset.UTC; }
        public Clock withZone(ZoneId z) { return this; }
        public Instant instant() { return time.get(); }
    };
    final InMemoryMetricsRecorder rec = new InMemoryMetricsRecorder(logIds, clock);
    final ToolExecutor executor = new ToolExecutor(Duration.ofMillis(200), logIds, rec);

    ToolCallContext ctx(TenantId t) {
        return new ToolCallContext(new AccountContext(t), "rid");
    }

    MetricsSnapshot.TenantMetrics tenant(TenantId t) {
        return rec.snapshot().tenants().stream().filter(m -> m.tenant().equals(logIds.tenant(t.value())))
                .findFirst().orElseThrow();
    }

    @Test
    void recordsPerToolLatencyForEveryCallWithFakeClock() {
        ToolHandler h = executor.wrap("search_messages", (cx, a) -> {
            time.updateAndGet(i -> i.plusMillis(250));
            return new ToolOutcome.Success(Map.of());
        });
        h.handle(ctx(T1), Map.of());
        MetricsSnapshot.OperationMetrics o = rec.snapshot().operations().getFirst();
        assertThat(o.tool()).isEqualTo("search_messages");
        assertThat(o.op()).isEqualTo("call");
        assertThat(o.tenant()).isEqualTo(logIds.tenant(T1.value()));
        assertThat(o.count()).isEqualTo(1);
        assertThat(o.avg()).isEqualTo(Duration.ofMillis(250));
    }

    @Test
    void authFailedCountsForThatTenantOnly() {
        executor.wrap("t", (cx, a) -> {
            throw new RuntimeException(new AuthenticationFailedException("nope"));
        }).handle(ctx(T1), Map.of());
        assertThat(tenant(T1).authFailures()).isEqualTo(1);
        assertThat(rec.snapshot().tenants()).noneMatch(m -> m.tenant().equals(logIds.tenant(T2.value())));
    }

    @Test
    void failureOutcomeFromHandlerWithAuthFailedIsCounted() {
        executor.wrap("t", (cx, a) -> new ToolOutcome.Failure(ToolError.authFailed("rid")))
                .handle(ctx(T2), Map.of());
        assertThat(tenant(T2).authFailures()).isEqualTo(1);
    }

    @Test
    void deadlineCountsAsTimeout() {
        executor.wrap("slow", (cx, a) -> {
            try {
                Thread.sleep(5_000);
            } catch (InterruptedException e) {
                // interrupted by the deadline
            }
            return new ToolOutcome.Success(Map.of());
        }).handle(ctx(T1), Map.of());
        assertThat(tenant(T1).timeouts()).isEqualTo(1);
    }

    @Test
    void otherErrorsAreNotCountedAsAuthOrTimeout() {
        executor.wrap("t", (cx, a) -> {
            throw new ServerBusyException();
        }).handle(ctx(T1), Map.of());
        assertThat(rec.snapshot().tenants()).isEmpty();
    }

    @Test
    void twoArgConstructorStillWorksWithoutMetrics() {
        ToolExecutor plain = new ToolExecutor(Duration.ofMillis(200), logIds);
        assertThat(plain.wrap("t", (cx, a) -> new ToolOutcome.Success(Map.of())).handle(ctx(T1), Map.of()))
                .isInstanceOf(ToolOutcome.Success.class);
    }

    @Test
    void externalCancelIsNotCountedAsTimeout() throws Exception {
        java.util.concurrent.CountDownLatch started = new java.util.concurrent.CountDownLatch(1);
        ToolCallContext c = ctx(T1);
        ToolExecutor longDeadline = new ToolExecutor(Duration.ofSeconds(5), logIds, rec);
        Thread caller = Thread.ofPlatform().start(() -> longDeadline.wrap("t", (cx, a) -> {
            started.countDown();
            try {
                Thread.sleep(10_000);
            } catch (InterruptedException e) {
                // cancelled
            }
            return new ToolOutcome.Success(Map.of());
        }).handle(c, Map.of()));
        started.await();
        c.cancel();
        caller.join(2000);
        assertThat(rec.snapshot().tenants()).isEmpty();
    }
}
