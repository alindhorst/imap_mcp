package de.alexanderlindhorst.imapmcp.core.tool;

import static org.assertj.core.api.Assertions.assertThat;

import de.alexanderlindhorst.imapmcp.core.crypto.KeyRing;
import de.alexanderlindhorst.imapmcp.core.crypto.LogIds;
import de.alexanderlindhorst.imapmcp.core.error.ErrorCode;
import de.alexanderlindhorst.imapmcp.core.error.ToolError;
import de.alexanderlindhorst.imapmcp.core.metrics.MetricsRecorder;
import de.alexanderlindhorst.imapmcp.core.tenancy.AccountContext;
import de.alexanderlindhorst.imapmcp.core.tenancy.TenantId;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

/** #195: outcomes produced by the executor (not the handler) are reported once, for write tools only. */
class ToolExecutorOutcomeListenerTest {

    record Seen(String tool, String requestId, ErrorCode code) {
    }

    final LogIds logIds = new LogIds(new KeyRing(1, Map.of(1, new byte[32])));
    final List<Seen> seen = new CopyOnWriteArrayList<>();
    final ToolExecutor executor = new ToolExecutor(Duration.ofMillis(200), logIds, MetricsRecorder.NOOP,
            (tool, rid, code, duration) -> {
                assertThat(duration).isNotNull();
                seen.add(new Seen(tool, rid, code));
            });

    ToolCallContext ctx() {
        return new ToolCallContext(new AccountContext(new TenantId("t")), "r1");
    }

    static ToolDefinition def(String name, ToolHints hints) {
        Map<String, Object> schema = Map.of("type", "object");
        return new ToolDefinition(name, "W", "d", schema, schema, hints);
    }

    static ToolHandler sleeping(boolean mark) {
        return (cx, a) -> {
            if (mark) {
                cx.markMutationStarted();
            }
            try {
                Thread.sleep(10_000);
            } catch (InterruptedException e) {
                // interrupted by the executor
            }
            return new ToolOutcome.Success(Map.of());
        };
    }

    @Test
    void writeDeadlineAfterMutationReportsTimeoutOnce() {
        executor.wrap(def("move_messages", ToolHints.WRITE_DESTRUCTIVE_IDEMPOTENT), sleeping(true))
                .handle(ctx(), Map.of());
        assertThat(seen).containsExactly(new Seen("move_messages", "r1", ErrorCode.TIMEOUT));
    }

    @Test
    void copyDeadlineAfterMutationReportsOutcomeUnknown() {
        executor.wrap(def("copy_messages", ToolHints.WRITE_ADDITIVE), sleeping(true)).handle(ctx(), Map.of());
        assertThat(seen).containsExactly(new Seen("copy_messages", "r1", ErrorCode.OUTCOME_UNKNOWN));
    }

    @Test
    void writeDeadlineBeforeMutationReportsTimeout() {
        executor.wrap(def("copy_messages", ToolHints.WRITE_ADDITIVE), sleeping(false)).handle(ctx(), Map.of());
        assertThat(seen).containsExactly(new Seen("copy_messages", "r1", ErrorCode.TIMEOUT));
    }

    @Test
    void uncheckedFailureReportsTheFinalCodeOnce() {
        ToolHandler boom = (cx, a) -> {
            cx.markMutationStarted();
            throw new IllegalStateException("x");
        };
        executor.wrap(def("copy_messages", ToolHints.WRITE_ADDITIVE), boom).handle(ctx(), Map.of());
        executor.wrap(def("set_flags", ToolHints.WRITE_DESTRUCTIVE_IDEMPOTENT), boom).handle(ctx(), Map.of());
        assertThat(seen).containsExactly(new Seen("copy_messages", "r1", ErrorCode.OUTCOME_UNKNOWN),
                new Seen("set_flags", "r1", ErrorCode.INTERNAL_ERROR));
    }

    @Test
    void externalCancelReportsOnce() throws Exception {
        ToolExecutor ex = new ToolExecutor(Duration.ofSeconds(5), logIds, MetricsRecorder.NOOP,
                (tool, rid, code, d) -> seen.add(new Seen(tool, rid, code)));
        CountDownLatch started = new CountDownLatch(1);
        ToolCallContext c = ctx();
        ToolHandler h = ex.wrap(def("copy_messages", ToolHints.WRITE_ADDITIVE), (cx, a) -> {
            cx.markMutationStarted();
            started.countDown();
            try {
                Thread.sleep(10_000);
            } catch (InterruptedException e) {
                // cancelled
            }
            return new ToolOutcome.Success(Map.of());
        });
        Thread caller = Thread.ofPlatform().start(() -> h.handle(c, Map.of()));
        assertThat(started.await(2, TimeUnit.SECONDS)).isTrue();
        c.cancel();
        caller.join(2000);
        assertThat(seen).containsExactly(new Seen("copy_messages", "r1", ErrorCode.OUTCOME_UNKNOWN));
    }

    @Test
    void readToolIsNeverReported() {
        executor.wrap(def("get_message", ToolHints.READ_ONLY_OPEN_WORLD), sleeping(false)).handle(ctx(), Map.of());
        executor.wrap(def("get_message", ToolHints.READ_ONLY_OPEN_WORLD), (cx, a) -> {
            throw new IllegalStateException("x");
        }).handle(ctx(), Map.of());
        executor.wrap("legacy", sleeping(false)).handle(ctx(), Map.of());
        assertThat(seen).isEmpty();
    }

    @Test
    void handlerReturnedOutcomesAreNotReported() {
        List<ToolOutcome> returned = new ArrayList<>();
        ToolDefinition d = def("move_messages", ToolHints.WRITE_DESTRUCTIVE_IDEMPOTENT);
        returned.add(executor.wrap(d, (cx, a) -> new ToolOutcome.Success(Map.of())).handle(ctx(), Map.of()));
        returned.add(executor.wrap(d, (cx, a) -> new ToolOutcome.Failure(ToolError.timeout("r1")))
                .handle(ctx(), Map.of()));
        assertThat(returned).hasSize(2);
        assertThat(seen).isEmpty();
    }

    @Test
    void listenerRunsWithTheWorkerMdcIncludingHashedTenantAndAccount() {
        java.util.concurrent.atomic.AtomicReference<Map<String, String>> mdc = new java.util.concurrent.atomic.AtomicReference<>();
        ToolExecutor ex = new ToolExecutor(Duration.ofMillis(200), logIds, MetricsRecorder.NOOP,
                (tool, rid, code, d) -> mdc.set(org.slf4j.MDC.getCopyOfContextMap()));
        ex.wrap(def("move_messages", ToolHints.WRITE_DESTRUCTIVE_IDEMPOTENT), (cx, a) -> {
            cx.bindAccount(new de.alexanderlindhorst.imapmcp.core.tenancy.AccountId("raw-acct"));
            cx.markMutationStarted();
            try {
                Thread.sleep(10_000);
            } catch (InterruptedException e) {
                // interrupted by the executor
            }
            return new ToolOutcome.Success(Map.of());
        }).handle(ctx(), Map.of());
        assertThat(mdc.get()).containsEntry("req", "r1").containsEntry("tool", "move_messages")
                .containsEntry("tenant", logIds.tenant("t")).containsEntry("acct", logIds.account("raw-acct"));
        assertThat(mdc.get().values()).doesNotContain("raw-acct", "t");
        assertThat(org.slf4j.MDC.getCopyOfContextMap()).isNullOrEmpty();
    }
}
