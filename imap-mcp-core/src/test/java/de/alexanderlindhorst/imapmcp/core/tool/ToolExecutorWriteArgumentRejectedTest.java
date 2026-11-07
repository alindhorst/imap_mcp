package de.alexanderlindhorst.imapmcp.core.tool;

import static org.assertj.core.api.Assertions.assertThat;

import de.alexanderlindhorst.imapmcp.core.audit.RecordingAuditLog;
import de.alexanderlindhorst.imapmcp.core.crypto.KeyRing;
import de.alexanderlindhorst.imapmcp.core.crypto.LogIds;
import de.alexanderlindhorst.imapmcp.core.error.ToolError;
import de.alexanderlindhorst.imapmcp.core.metrics.InMemoryMetricsRecorder;
import de.alexanderlindhorst.imapmcp.core.metrics.MetricsSnapshot;
import de.alexanderlindhorst.imapmcp.core.tenancy.AccountContext;
import de.alexanderlindhorst.imapmcp.core.tenancy.TenantId;
import java.time.Clock;
import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** D10 (SPEC 5.3, 5.6): a write call failing check-order step 1 is counted as write_argument_rejected, in every mode. */
class ToolExecutorWriteArgumentRejectedTest {

    static final TenantId T1 = new TenantId("t1");
    static final TenantId T2 = new TenantId("t2");
    final LogIds logIds = new LogIds(new KeyRing(1, Map.of(1, new byte[32])));
    final InMemoryMetricsRecorder rec = new InMemoryMetricsRecorder(logIds, Clock.systemUTC());
    final RecordingAuditLog audit = new RecordingAuditLog();
    final ToolExecutor executor = new ToolExecutor(Duration.ofSeconds(5), logIds, rec, (t, r, c, d) -> { }, audit);

    static ToolDefinition def(String name, ToolHints hints) {
        Map<String, Object> schema = Map.of("type", "object");
        return new ToolDefinition(name, "W", "d", schema, schema, hints);
    }

    static ToolCallContext ctx(TenantId t) {
        return new ToolCallContext(new AccountContext(t), "r1");
    }

    long rejected(TenantId t, String tool) {
        return rec.snapshot().operations().stream()
                .filter(o -> o.tenant().equals(logIds.tenant(t.value())) && o.tool().equals(tool)
                        && o.op().equals("write_argument_rejected"))
                .mapToLong(MetricsSnapshot.OperationMetrics::count).sum();
    }

    ToolHandler rejecting() {
        return (cx, a) -> {
            cx.rejectArguments();
            return new ToolOutcome.Failure(ToolError.invalidArgument("handles", "out_of_range", 100, "r1"));
        };
    }

    @Test
    void aRejectedWriteCallIsCountedPerHashedTenantAndTool() {
        ToolDefinition move = def("move_messages", ToolHints.WRITE_DESTRUCTIVE_IDEMPOTENT);
        executor.wrap(move, rejecting()).handle(ctx(T1), Map.of());
        executor.wrap(move, rejecting()).handle(ctx(T1), Map.of());
        executor.wrap(def("copy_messages", ToolHints.WRITE_ADDITIVE), rejecting()).handle(ctx(T1), Map.of());
        assertThat(rejected(T1, "move_messages")).isEqualTo(2);
        assertThat(rejected(T1, "copy_messages")).isEqualTo(1);
        assertThat(rejected(T2, "move_messages")).isZero();
        assertThat(audit.order).isEmpty();
    }

    @Test
    void itIsCountedWithoutAnAuditLogToo() {
        ToolExecutor plain = new ToolExecutor(Duration.ofSeconds(5), logIds, rec);
        plain.wrap(def("move_messages", ToolHints.WRITE_DESTRUCTIVE_IDEMPOTENT), rejecting()).handle(ctx(T1), Map.of());
        assertThat(rejected(T1, "move_messages")).isEqualTo(1);
    }

    @Test
    void laterErrorsSuccessesAndReadToolsAreNotCounted() {
        ToolDefinition move = def("move_messages", ToolHints.WRITE_DESTRUCTIVE_IDEMPOTENT);
        executor.wrap(move, (cx, a) -> new ToolOutcome.Failure(ToolError.invalidArgument("handles[1]", "duplicate",
                null, "r1"))).handle(ctx(T1), Map.of());
        executor.wrap(move, (cx, a) -> new ToolOutcome.Success(Map.of())).handle(ctx(T1), Map.of());
        executor.wrap(def("get_message", ToolHints.READ_ONLY_OPEN_WORLD), rejecting()).handle(ctx(T1), Map.of());
        assertThat(rejected(T1, "move_messages")).isZero();
        assertThat(rejected(T1, "get_message")).isZero();
    }

    @Test
    void theSummaryLineCarriesTheCounterWithHashedIdsOnly() {
        executor.wrap(def("move_messages", ToolHints.WRITE_DESTRUCTIVE_IDEMPOTENT), rejecting()).handle(ctx(T1), Map.of());
        assertThat(rec.snapshot().operations()).anySatisfy(o -> {
            assertThat(o.op()).isEqualTo("write_argument_rejected");
            assertThat(o.tenant()).doesNotContain("t1");
        });
    }
}
