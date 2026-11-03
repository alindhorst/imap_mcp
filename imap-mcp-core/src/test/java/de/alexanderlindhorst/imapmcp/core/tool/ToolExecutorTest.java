package de.alexanderlindhorst.imapmcp.core.tool;

import static org.assertj.core.api.Assertions.assertThat;

import de.alexanderlindhorst.imapmcp.core.crypto.KeyRing;
import de.alexanderlindhorst.imapmcp.core.crypto.LogIds;
import de.alexanderlindhorst.imapmcp.core.error.ErrorCode;
import de.alexanderlindhorst.imapmcp.core.error.ToolError;
import de.alexanderlindhorst.imapmcp.core.tenancy.AccountContext;
import de.alexanderlindhorst.imapmcp.core.tenancy.AccountId;
import de.alexanderlindhorst.imapmcp.core.tenancy.TenantId;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;

class ToolExecutorTest {

    static final String TENANT = "raw-tenant-id";
    final LogIds logIds = new LogIds(new KeyRing(1, Map.of(1, new byte[32])));
    final ToolExecutor executor = new ToolExecutor(Duration.ofMillis(200), logIds);

    ToolCallContext ctx(String rid) {
        return new ToolCallContext(new AccountContext(new TenantId(TENANT)), rid);
    }

    static ToolOutcome ok() {
        return new ToolOutcome.Success(Map.of());
    }

    @Test
    void deadlineYieldsTimeoutInterruptsWorkerAndRunsHooksOnce() throws Exception {
        AtomicBoolean interrupted = new AtomicBoolean();
        CountDownLatch done = new CountDownLatch(1);
        AtomicInteger hooks = new AtomicInteger();
        ToolCallContext c = ctx("r1");
        c.onCancel(hooks::incrementAndGet);
        ToolHandler h = executor.wrap("slow", (cx, a) -> {
            try {
                Thread.sleep(10_000);
            } catch (InterruptedException e) {
                interrupted.set(true);
            }
            done.countDown();
            return ok();
        });
        long t0 = System.nanoTime();
        ToolOutcome o = h.handle(c, Map.of());
        long ms = (System.nanoTime() - t0) / 1_000_000;
        assertThat(ms).isLessThan(700);
        assertThat(((ToolOutcome.Failure) o).error().code()).isEqualTo(ErrorCode.TIMEOUT);
        assertThat(done.await(2, TimeUnit.SECONDS)).isTrue();
        assertThat(interrupted).isTrue();
        assertThat(hooks).hasValue(1);
        assertThat(c.isCancelled()).isTrue();
    }

    @Test
    void runsOnVirtualThread() {
        AtomicBoolean virtual = new AtomicBoolean();
        executor.wrap("t", (cx, a) -> {
            virtual.set(Thread.currentThread().isVirtual());
            return ok();
        }).handle(ctx("r"), Map.of());
        assertThat(virtual).isTrue();
    }

    @Test
    void mdcInsideHandlerAndEmptyOnCaller() {
        AtomicReference<Map<String, String>> seen = new AtomicReference<>();
        executor.wrap("my_tool", (cx, a) -> {
            cx.bindAccount(new AccountId("acc-1"));
            seen.set(MDC.getCopyOfContextMap());
            return ok();
        }).handle(ctx("rid-7"), Map.of());
        assertThat(seen.get()).containsEntry("req", "rid-7").containsEntry("tool", "my_tool")
                .containsEntry("tenant", logIds.tenant(TENANT)).containsEntry("acct", logIds.account("acc-1"));
        assertThat(seen.get().get("tenant")).matches("[0-9a-f]{12}");
        assertThat(seen.get().values()).doesNotContain(TENANT);
        assertThat(MDC.getCopyOfContextMap()).isNullOrEmpty();
    }

    @Test
    void runtimeExceptionBecomesInternalErrorWithoutMessage() {
        ToolOutcome o = executor.wrap("t", (cx, a) -> {
            throw new RuntimeException("marker-secret");
        }).handle(ctx("rid-42"), Map.of());
        var err = ((ToolOutcome.Failure) o).error();
        assertThat(err.code()).isEqualTo(ErrorCode.INTERNAL_ERROR);
        assertThat(err.toMap().toString()).doesNotContain("marker-secret").contains("rid-42");
    }

    @Test
    void concurrentCallsKeepMdcSeparate() throws Exception {
        ToolExecutor ex = new ToolExecutor(Duration.ofSeconds(5), logIds);
        CountDownLatch both = new CountDownLatch(2);
        AtomicReference<String> reqA = new AtomicReference<>();
        AtomicReference<String> reqB = new AtomicReference<>();
        ToolHandler h = ex.wrap("t", (cx, a) -> {
            both.countDown();
            try {
                both.await(2, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            (cx.requestId().equals("A") ? reqA : reqB).set(MDC.get("req"));
            return ok();
        });
        Thread ta = Thread.ofPlatform().start(() -> h.handle(ctx("A"), Map.of()));
        Thread tb = Thread.ofPlatform().start(() -> h.handle(ctx("B"), Map.of()));
        ta.join();
        tb.join();
        assertThat(reqA).hasValue("A");
        assertThat(reqB).hasValue("B");
    }

    /** Loops on the latch and swallows interrupts, like a handler stuck in uninterruptible I/O. */
    static ToolHandler stubborn(CountDownLatch started, CountDownLatch release, AtomicInteger interrupts) {
        return (cx, a) -> {
            started.countDown();
            while (true) {
                try {
                    if (release.await(10, TimeUnit.SECONDS)) {
                        return ok();
                    }
                } catch (InterruptedException e) {
                    interrupts.incrementAndGet();
                }
            }
        };
    }

    @Test
    void externalCancelReturnsTimeoutEvenIfHandlerIgnoresInterruptAndLaterSucceeds() throws Exception {
        ToolExecutor ex = new ToolExecutor(Duration.ofSeconds(5), logIds);
        AtomicInteger hooks = new AtomicInteger();
        AtomicInteger interrupts = new AtomicInteger();
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicReference<ToolOutcome> outcome = new AtomicReference<>();
        ToolCallContext c = ctx("r");
        c.onCancel(hooks::incrementAndGet);
        ToolHandler h = ex.wrap("t", stubborn(started, release, interrupts));
        Thread caller = Thread.ofPlatform().start(() -> outcome.set(h.handle(c, Map.of())));
        assertThat(started.await(2, TimeUnit.SECONDS)).isTrue();
        long t0 = System.nanoTime();
        c.cancel();
        c.cancel();
        caller.join(500);
        assertThat(caller.isAlive()).isFalse();
        assertThat((System.nanoTime() - t0) / 1_000_000).isLessThan(500);
        assertThat(((ToolOutcome.Failure) outcome.get()).error().code()).isEqualTo(ErrorCode.TIMEOUT);
        release.countDown(); // the handler now returns Success; it must never surface
        assertThat(hooks).hasValue(1);
        assertThat(interrupts.get()).isLessThanOrEqualTo(1);
        assertThat(outcome.get()).isInstanceOf(ToolOutcome.Failure.class);
    }

    @Test
    void deadlineWithStubbornHandlerThenCancelRunsHooksOnce() throws Exception {
        AtomicInteger hooks = new AtomicInteger();
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        ToolCallContext c = ctx("r");
        c.onCancel(hooks::incrementAndGet);
        ToolOutcome o = executor.wrap("t", stubborn(started, release, new AtomicInteger())).handle(c, Map.of());
        assertThat(((ToolOutcome.Failure) o).error().code()).isEqualTo(ErrorCode.TIMEOUT);
        c.cancel();
        release.countDown();
        assertThat(hooks).hasValue(1);
    }

    @Test
    void callCancelledBeforeStartLaunchesNoWorker() throws Exception {
        AtomicInteger handled = new AtomicInteger();
        ToolCallContext c = ctx("r");
        c.cancel();
        ToolOutcome o = executor.wrap("t", (cx, a) -> {
            handled.incrementAndGet();
            return ok();
        }).handle(c, Map.of());
        assertThat(((ToolOutcome.Failure) o).error().code()).isEqualTo(ErrorCode.TIMEOUT);
        Thread.sleep(100);
        assertThat(handled).hasValue(0);
    }

    @Test
    void executorExposesTheCallDeadlineToTheHandler() {
        AtomicReference<java.util.Optional<java.time.Instant>> seen = new AtomicReference<>();
        java.time.Instant before = java.time.Instant.now();
        executor.wrap("t", (cx, a) -> {
            seen.set(cx.deadline());
            return ok();
        }).handle(ctx("r"), Map.of());
        java.time.Instant after = java.time.Instant.now();
        assertThat(seen.get()).isPresent();
        assertThat(seen.get().get()).isBetween(before.plusMillis(200), after.plusMillis(200));
    }

    // --- v2 mutationStarted (SPEC 5.5 Write calls) ---

    static ToolDefinition def(ToolHints hints) {
        Map<String, Object> schema = Map.of("type", "object");
        return new ToolDefinition("w", "W", "d", schema, schema, hints);
    }

    static ToolHandler sleepingAfter(boolean mark, CountDownLatch started) {
        return (cx, a) -> {
            if (mark) {
                cx.markMutationStarted();
            }
            started.countDown();
            try {
                Thread.sleep(10_000);
            } catch (InterruptedException e) {
                // interrupted by the executor
            }
            return ok();
        };
    }

    static ToolError errorOf(ToolOutcome o) {
        return ((ToolOutcome.Failure) o).error();
    }

    @Test
    void deadlineBeforeMarkIsTheV1TimeoutPayload() {
        ToolOutcome o = executor.wrap(def(ToolHints.WRITE_DESTRUCTIVE_IDEMPOTENT),
                sleepingAfter(false, new CountDownLatch(1))).handle(ctx("r"), Map.of());
        assertThat(errorOf(o)).isEqualTo(ToolError.timeout("r"));
        assertThat(errorOf(o).details()).isNull();
    }

    @Test
    void deadlineAfterMarkOnIdempotentToolIsRetryableTimeoutWithOutcomeUnknown() {
        ToolOutcome o = executor.wrap(def(ToolHints.WRITE_DESTRUCTIVE_IDEMPOTENT),
                sleepingAfter(true, new CountDownLatch(1))).handle(ctx("r"), Map.of());
        assertThat(errorOf(o)).isEqualTo(ToolError.timeout("r").withOutcomeUnknown());
        assertThat(errorOf(o).retryable()).isTrue();
        assertThat(errorOf(o).details()).containsEntry("outcome", "unknown");
    }

    @Test
    void deadlineAfterMarkOnCopyToolIsOutcomeUnknownNotRetryable() {
        ToolOutcome o = executor.wrap(def(ToolHints.WRITE_ADDITIVE), sleepingAfter(true, new CountDownLatch(1)))
                .handle(ctx("r"), Map.of());
        assertThat(errorOf(o)).isEqualTo(ToolError.outcomeUnknown("r"));
        assertThat(errorOf(o).retryable()).isFalse();
    }

    @Test
    void externalCancelAfterMarkMapsLikeTheDeadlineAndIsNotCountedAsTimeout() throws Exception {
        ToolExecutor ex = new ToolExecutor(Duration.ofSeconds(5), logIds);
        for (boolean idempotent : new boolean[] {true, false}) {
            CountDownLatch started = new CountDownLatch(1);
            AtomicReference<ToolOutcome> outcome = new AtomicReference<>();
            ToolCallContext c = ctx("r");
            ToolHandler h = ex.wrap(def(idempotent ? ToolHints.WRITE_ADDITIVE_IDEMPOTENT : ToolHints.WRITE_ADDITIVE),
                    sleepingAfter(true, started));
            Thread caller = Thread.ofPlatform().start(() -> outcome.set(h.handle(c, Map.of())));
            assertThat(started.await(2, TimeUnit.SECONDS)).isTrue();
            c.cancel();
            caller.join(2000);
            assertThat(errorOf(outcome.get())).isEqualTo(idempotent ? ToolError.timeout("r").withOutcomeUnknown()
                    : ToolError.outcomeUnknown("r"));
        }
    }

    @Test
    void externalCancelBeforeMarkIsTheV1Timeout() throws Exception {
        ToolExecutor ex = new ToolExecutor(Duration.ofSeconds(5), logIds);
        CountDownLatch started = new CountDownLatch(1);
        AtomicReference<ToolOutcome> outcome = new AtomicReference<>();
        ToolCallContext c = ctx("r");
        ToolHandler h = ex.wrap(def(ToolHints.WRITE_ADDITIVE), sleepingAfter(false, started));
        Thread caller = Thread.ofPlatform().start(() -> outcome.set(h.handle(c, Map.of())));
        assertThat(started.await(2, TimeUnit.SECONDS)).isTrue();
        c.cancel();
        caller.join(2000);
        assertThat(errorOf(outcome.get())).isEqualTo(ToolError.timeout("r"));
    }

    @Test
    void uncheckedExceptionAfterMarkAddsOutcomeUnknown() {
        ToolHandler boom = (cx, a) -> {
            cx.markMutationStarted();
            throw new IllegalStateException("marker-secret");
        };
        ToolError idem = errorOf(executor.wrap(def(ToolHints.WRITE_DESTRUCTIVE_IDEMPOTENT), boom).handle(ctx("r"), Map.of()));
        assertThat(idem.code()).isEqualTo(ErrorCode.INTERNAL_ERROR);
        assertThat(idem.details()).containsEntry("outcome", "unknown");
        assertThat(idem.toMap().toString()).doesNotContain("marker-secret");
        assertThat(errorOf(executor.wrap(def(ToolHints.WRITE_ADDITIVE), boom).handle(ctx("r"), Map.of())))
                .isEqualTo(ToolError.outcomeUnknown("r"));
    }

    @Test
    void connectionFailureAfterMarkKeepsUpstreamUnavailableCodeWithOutcomeUnknown() {
        ToolHandler down = (cx, a) -> {
            cx.markMutationStarted();
            throw new RuntimeException(new java.net.ConnectException("x"));
        };
        ToolError e = errorOf(executor.wrap(def(ToolHints.WRITE_DESTRUCTIVE_IDEMPOTENT), down).handle(ctx("r"), Map.of()));
        assertThat(e.code()).isEqualTo(ErrorCode.UPSTREAM_UNAVAILABLE);
        assertThat(e.details()).containsEntry("outcome", "unknown");
    }

    @Test
    void uncheckedExceptionBeforeMarkIsTheV1Backstop() {
        ToolError e = errorOf(executor.wrap(def(ToolHints.WRITE_ADDITIVE), (cx, a) -> {
            throw new IllegalStateException("x");
        }).handle(ctx("r"), Map.of()));
        assertThat(e).isEqualTo(ToolError.internalError("r"));
    }

    @Test
    void resultProducedAfterDeadlineIsNeverReturnedForWriteTools() throws Exception {
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        ToolOutcome o = executor.wrap(def(ToolHints.WRITE_DESTRUCTIVE_IDEMPOTENT), (cx, a) -> {
            cx.markMutationStarted();
            return stubborn(started, release, new AtomicInteger()).handle(cx, a);
        }).handle(ctx("r"), Map.of());
        release.countDown();
        assertThat(errorOf(o).code()).isEqualTo(ErrorCode.TIMEOUT);
    }

    @Test
    void readToolsNeverMarkAndKeepV1Outcomes() {
        ToolOutcome o = executor.wrap(def(ToolHints.READ_ONLY_OPEN_WORLD), sleepingAfter(false, new CountDownLatch(1)))
                .handle(ctx("r"), Map.of());
        assertThat(errorOf(o)).isEqualTo(ToolError.timeout("r"));
    }
}
