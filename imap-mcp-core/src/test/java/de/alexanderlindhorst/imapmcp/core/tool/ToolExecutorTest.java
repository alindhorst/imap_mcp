package de.alexanderlindhorst.imapmcp.core.tool;

import static org.assertj.core.api.Assertions.assertThat;

import de.alexanderlindhorst.imapmcp.core.crypto.KeyRing;
import de.alexanderlindhorst.imapmcp.core.crypto.LogIds;
import de.alexanderlindhorst.imapmcp.core.error.ErrorCode;
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
        assertThat(ms).isLessThan(300);
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

    @Test
    void externalCancelRunsHooksOnceAndInterruptsWorker() throws Exception {
        ToolExecutor ex = new ToolExecutor(Duration.ofSeconds(5), logIds);
        AtomicInteger hooks = new AtomicInteger();
        CountDownLatch started = new CountDownLatch(1);
        AtomicBoolean interrupted = new AtomicBoolean();
        ToolCallContext c = ctx("r");
        c.onCancel(hooks::incrementAndGet);
        ToolHandler h = ex.wrap("t", (cx, a) -> {
            started.countDown();
            try {
                Thread.sleep(10_000);
            } catch (InterruptedException e) {
                interrupted.set(true);
            }
            return ok();
        });
        Thread caller = Thread.ofPlatform().start(() -> h.handle(c, Map.of()));
        assertThat(started.await(2, TimeUnit.SECONDS)).isTrue();
        c.cancel();
        c.cancel();
        caller.join(2000);
        assertThat(interrupted).isTrue();
        assertThat(hooks).hasValue(1);
    }
}
