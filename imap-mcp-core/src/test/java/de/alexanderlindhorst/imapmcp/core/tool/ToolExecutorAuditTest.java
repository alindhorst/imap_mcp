package de.alexanderlindhorst.imapmcp.core.tool;

import static org.assertj.core.api.Assertions.assertThat;

import de.alexanderlindhorst.imapmcp.core.audit.ItemRef;
import de.alexanderlindhorst.imapmcp.core.audit.RecordingAuditLog;
import de.alexanderlindhorst.imapmcp.core.crypto.KeyRing;
import de.alexanderlindhorst.imapmcp.core.crypto.LogIds;
import de.alexanderlindhorst.imapmcp.core.error.ErrorCode;
import de.alexanderlindhorst.imapmcp.core.metrics.MetricsRecorder;
import de.alexanderlindhorst.imapmcp.core.tenancy.AccountContext;
import de.alexanderlindhorst.imapmcp.core.tenancy.TenantId;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;

/** The executor hands the audit log to write tools only; a failed audit write is INTERNAL_ERROR, nothing marked. */
class ToolExecutorAuditTest {

    final LogIds logIds = new LogIds(new KeyRing(1, Map.of(1, new byte[32])));
    final RecordingAuditLog audit = new RecordingAuditLog();
    final ToolExecutor executor = new ToolExecutor(Duration.ofSeconds(5), logIds, MetricsRecorder.NOOP,
            (tool, rid, code, duration) -> { }, audit);

    ToolCallContext ctx() {
        return new ToolCallContext(new AccountContext(new TenantId("t")), "r1");
    }

    static ToolDefinition def(String name, ToolHints hints) {
        Map<String, Object> schema = Map.of("type", "object");
        return new ToolDefinition(name, "W", "d", schema, schema, hints);
    }

    @Test
    void writeToolsGetTheAuditLogReadToolsDoNot() {
        AtomicBoolean writeSees = new AtomicBoolean();
        AtomicBoolean readSees = new AtomicBoolean();
        executor.wrap(def("move_messages", ToolHints.WRITE_DESTRUCTIVE_IDEMPOTENT), (cx, a) -> {
            writeSees.set(cx.audit().enabled());
            return new ToolOutcome.Success(Map.of());
        }).handle(ctx(), Map.of());
        executor.wrap(def("get_message", ToolHints.READ_ONLY_OPEN_WORLD), (cx, a) -> {
            readSees.set(cx.audit().enabled());
            return new ToolOutcome.Success(Map.of());
        }).handle(ctx(), Map.of());
        assertThat(writeSees).isTrue();
        assertThat(readSees).isFalse();
    }

    @Test
    void withoutAnAuditLogWriteToolsRunUnaudited() {
        ToolExecutor plain = new ToolExecutor(Duration.ofSeconds(5), logIds);
        AtomicBoolean sees = new AtomicBoolean(true);
        plain.wrap(def("move_messages", ToolHints.WRITE_DESTRUCTIVE_IDEMPOTENT), (cx, a) -> {
            sees.set(cx.audit().enabled());
            return new ToolOutcome.Success(Map.of());
        }).handle(ctx(), Map.of());
        assertThat(sees).isFalse();
    }

    @Test
    void failedAuditWriteIsInternalErrorWithoutAnOutcomeMarker() {
        audit.failBegin = true;
        AtomicBoolean mutated = new AtomicBoolean();
        ToolOutcome out = executor.wrap(def("move_messages", ToolHints.WRITE_DESTRUCTIVE_IDEMPOTENT), (cx, a) -> {
            cx.audit().begin(List.of(new ItemRef("INBOX", 1, 1)));
            mutated.set(cx.tryBeginMutation());
            return new ToolOutcome.Success(Map.of());
        }).handle(ctx(), Map.of());
        assertThat(mutated).isFalse();
        assertThat(out).isInstanceOfSatisfying(ToolOutcome.Failure.class, f -> {
            assertThat(f.error().code()).isEqualTo(ErrorCode.INTERNAL_ERROR);
            assertThat(f.error().details() == null || !f.error().details().containsKey("outcome")).isTrue();
        });
    }

    @Test
    void anUncheckedFailureAfterBeginCompletesTheRowsAsAborted() {
        ToolOutcome out = executor.wrap(def("move_messages", ToolHints.WRITE_DESTRUCTIVE_IDEMPOTENT), (cx, a) -> {
            cx.audit().begin(List.of(new ItemRef("INBOX", 1, 1)));
            cx.tryBeginMutation();
            throw new IllegalStateException("boom");
        }).handle(ctx(), Map.of());
        assertThat(out).isInstanceOf(ToolOutcome.Failure.class);
        assertThat(audit.completions).singleElement().satisfies(c -> {
            assertThat(c.result()).isEqualTo("unknown");
            assertThat(c.errorCode()).isEqualTo("INTERNAL_ERROR");
        });
    }

    @Test
    void aNonIdempotentToolCompletesAbortedRowsWithOutcomeUnknown() {
        executor.wrap(def("copy_messages", ToolHints.WRITE_ADDITIVE), (cx, a) -> {
            cx.audit().begin(List.of(new ItemRef("INBOX", 1, 1)));
            cx.tryBeginMutation();
            throw new IllegalStateException("boom");
        }).handle(ctx(), Map.of());
        assertThat(audit.completions.get(0).errorCode()).isEqualTo("OUTCOME_UNKNOWN");
    }

    @Test
    void aFailureOutcomeAfterBeginCompletesTheRowsWithTheMappedCode() {
        executor.wrap(def("move_messages", ToolHints.WRITE_DESTRUCTIVE_IDEMPOTENT), (cx, a) -> {
            cx.audit().begin(List.of(new ItemRef("INBOX", 1, 1)));
            return new ToolOutcome.Failure(de.alexanderlindhorst.imapmcp.core.error.ToolError.timeout("r1"));
        }).handle(ctx(), Map.of());
        assertThat(audit.completions).singleElement().satisfies(c -> {
            assertThat(c.errorCode()).isEqualTo("TIMEOUT");
            assertThat(c.items()).containsExactly(new de.alexanderlindhorst.imapmcp.core.audit.ItemResult(
                    "not_attempted", "TIMEOUT"));
        });
    }

    @Test
    void aWholeCallErrorBeforeAnyRowGetsOneItemLessCallRow() {
        ToolOutcome out = executor.wrap(def("move_messages", ToolHints.WRITE_DESTRUCTIVE_IDEMPOTENT), (cx, a) -> {
            cx.bindAccount(new de.alexanderlindhorst.imapmcp.core.tenancy.AccountId("acc-1"));
            return new ToolOutcome.Failure(de.alexanderlindhorst.imapmcp.core.error.ToolError.invalidHandle(
                    "handles[0]", "r1"));
        }).handle(ctx(), Map.of());
        assertThat(out).isInstanceOf(ToolOutcome.Failure.class);
        assertThat(audit.order).containsExactly("begin", "complete");
        assertThat(audit.begun.get(0)).isEmpty();
        assertThat(audit.calls.get(0).tool()).isEqualTo("move_messages");
        assertThat(audit.calls.get(0).account()).isEqualTo(new de.alexanderlindhorst.imapmcp.core.tenancy.AccountId("acc-1"));
        assertThat(audit.completions.get(0).result()).isEqualTo("failed");
        assertThat(audit.completions.get(0).errorCode()).isEqualTo("INVALID_HANDLE");
    }

    static final String CANARY = "acc_CANARYHANDLEZZZZZZZZZZZZZZ";

    ToolCallContext ctxFor(TenantId tenant) {
        return new ToolCallContext(new AccountContext(tenant), "r1");
    }

    @Test
    void aCallEndingBeforeItsAccountIsResolvedGetsOneRowWithoutAccountId() {
        ToolOutcome out = executor.wrap(def("move_messages", ToolHints.WRITE_DESTRUCTIVE_IDEMPOTENT), (cx, a) ->
                new ToolOutcome.Failure(de.alexanderlindhorst.imapmcp.core.error.ToolError.accountNotFound("r1")))
                .handle(ctx(), Map.of("account", CANARY));
        assertThat(out).isInstanceOf(ToolOutcome.Failure.class);
        assertThat(audit.order).containsExactly("begin", "complete");
        assertThat(audit.begun.get(0)).isEmpty();
        assertThat(audit.calls.get(0).tenant()).isEqualTo(new TenantId("t"));
        assertThat(audit.calls.get(0).account()).isNull();
        assertThat(audit.calls.get(0).tool()).isEqualTo("move_messages");
        assertThat(audit.completions.get(0).result()).isEqualTo("failed");
        assertThat(audit.completions.get(0).errorCode()).isEqualTo("ACCOUNT_NOT_FOUND");
        assertThat(audit.completions.get(0).items()).isEmpty();
        assertThat(audit.calls.toString() + audit.completions + audit.begun).doesNotContain(CANARY);
    }

    @Test
    void anInternalErrorDuringTheAccountLookupGetsTheRowToo() {
        executor.wrap(def("move_messages", ToolHints.WRITE_DESTRUCTIVE_IDEMPOTENT), (cx, a) -> {
            throw new IllegalStateException("lookup failed");
        }).handle(ctx(), Map.of());
        assertThat(audit.completions).singleElement().satisfies(c -> assertThat(c.errorCode()).isEqualTo("INTERNAL_ERROR"));
        assertThat(audit.calls.get(0).account()).isNull();
    }

    @Test
    void aDeadlineDuringTheAccountLookupGetsTheRowToo() {
        ToolExecutor quick = new ToolExecutor(Duration.ofMillis(100), logIds, MetricsRecorder.NOOP,
                (tool, rid, code, duration) -> { }, audit);
        quick.wrap(def("move_messages", ToolHints.WRITE_DESTRUCTIVE_IDEMPOTENT), (cx, a) -> {
            try {
                Thread.sleep(5_000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return new ToolOutcome.Success(Map.of());
        }).handle(ctx(), Map.of());
        assertThat(audit.completions).singleElement().satisfies(c -> assertThat(c.errorCode()).isEqualTo("TIMEOUT"));
        assertThat(audit.calls.get(0).account()).isNull();
    }

    @Test
    void aStepOneRejectionBeforeTheAccountStillWritesNoRow() {
        executor.wrap(def("move_messages", ToolHints.WRITE_DESTRUCTIVE_IDEMPOTENT), (cx, a) -> {
            cx.rejectArguments();
            return new ToolOutcome.Failure(de.alexanderlindhorst.imapmcp.core.error.ToolError.invalidArgument(
                    "handles", "out_of_range", 100, "r1"));
        }).handle(ctx(), Map.of());
        assertThat(audit.order).isEmpty();
    }

    @Test
    void aPrincipalWithoutATenantRowGetsNoRowForNow() {
        // on hold (owner question, D11): rows for TenantId.NONE are switched by CallAudit.ROWS_FOR_TENANT_NONE
        executor.wrap(def("move_messages", ToolHints.WRITE_DESTRUCTIVE_IDEMPOTENT), (cx, a) ->
                new ToolOutcome.Failure(de.alexanderlindhorst.imapmcp.core.error.ToolError.accountNotFound("r1")))
                .handle(ctxFor(TenantId.NONE), Map.of());
        assertThat(audit.order).isEmpty();
    }

    @Test
    void theRowWithoutAccountIsNotFailClosedAndTheOriginalErrorStays() {
        audit.failBegin = true;
        ToolOutcome out = executor.wrap(def("move_messages", ToolHints.WRITE_DESTRUCTIVE_IDEMPOTENT), (cx, a) ->
                new ToolOutcome.Failure(de.alexanderlindhorst.imapmcp.core.error.ToolError.accountNotFound("r1")))
                .handle(ctx(), Map.of());
        assertThat(out).isInstanceOfSatisfying(ToolOutcome.Failure.class,
                f -> assertThat(f.error().code()).isEqualTo(ErrorCode.ACCOUNT_NOT_FOUND));
    }

    @Test
    void aSuccessWithoutAnAccountWritesNothing() {
        executor.wrap(def("move_messages", ToolHints.WRITE_DESTRUCTIVE_IDEMPOTENT), (cx, a) ->
                new ToolOutcome.Success(Map.of())).handle(ctx(), Map.of());
        assertThat(audit.order).isEmpty();
    }

    @Test
    void thatRowIsNotFailClosedAndTheOriginalErrorStays() {
        audit.failBegin = true;
        ToolOutcome out = executor.wrap(def("move_messages", ToolHints.WRITE_DESTRUCTIVE_IDEMPOTENT), (cx, a) -> {
            cx.bindAccount(new de.alexanderlindhorst.imapmcp.core.tenancy.AccountId("acc-1"));
            return new ToolOutcome.Failure(de.alexanderlindhorst.imapmcp.core.error.ToolError.invalidHandle(
                    "handles[0]", "r1"));
        }).handle(ctx(), Map.of());
        assertThat(out).isInstanceOfSatisfying(ToolOutcome.Failure.class,
                f -> assertThat(f.error().code()).isEqualTo(ErrorCode.INVALID_HANDLE));
    }

    @Test
    void successesAndReadToolFailuresWriteNoCallRow() {
        executor.wrap(def("move_messages", ToolHints.WRITE_DESTRUCTIVE_IDEMPOTENT), (cx, a) ->
                new ToolOutcome.Success(Map.of())).handle(ctx(), Map.of());
        executor.wrap(def("get_message", ToolHints.READ_ONLY_OPEN_WORLD), (cx, a) ->
                new ToolOutcome.Failure(de.alexanderlindhorst.imapmcp.core.error.ToolError.invalidHandle(
                        "handles[0]", "r1"))).handle(ctx(), Map.of());
        assertThat(audit.order).isEmpty();
    }

    @Test
    void aFailedBeginIsNotRetriedAsACallRow() {
        audit.failBegin = true;
        executor.wrap(def("move_messages", ToolHints.WRITE_DESTRUCTIVE_IDEMPOTENT), (cx, a) -> {
            cx.audit().begin(List.of(new ItemRef("INBOX", 1, 1)));
            return new ToolOutcome.Success(Map.of());
        }).handle(ctx(), Map.of());
        audit.failBegin = false;
        assertThat(audit.order).isEmpty();
    }

    @Test
    void aStepOneRejectionWritesNoRowEvenWithAResolvedAccount() {
        ToolOutcome out = executor.wrap(def("move_messages", ToolHints.WRITE_DESTRUCTIVE_IDEMPOTENT), (cx, a) -> {
            cx.bindAccount(new de.alexanderlindhorst.imapmcp.core.tenancy.AccountId("acc-1"));
            cx.rejectArguments();
            return new ToolOutcome.Failure(de.alexanderlindhorst.imapmcp.core.error.ToolError.invalidArgument(
                    "handles", "out_of_range", 100, "r1"));
        }).handle(ctx(), Map.of());
        assertThat(out).isInstanceOf(ToolOutcome.Failure.class);
        assertThat(audit.order).isEmpty();
    }

    @Test
    void aLaterInvalidArgumentWithoutTheMarkerStillWritesItsRow() {
        executor.wrap(def("move_messages", ToolHints.WRITE_DESTRUCTIVE_IDEMPOTENT), (cx, a) -> {
            cx.bindAccount(new de.alexanderlindhorst.imapmcp.core.tenancy.AccountId("acc-1"));
            return new ToolOutcome.Failure(de.alexanderlindhorst.imapmcp.core.error.ToolError.invalidArgument(
                    "handles[1]", "duplicate", null, "r1"));
        }).handle(ctx(), Map.of());
        assertThat(audit.completions).singleElement()
                .satisfies(c -> assertThat(c.errorCode()).isEqualTo("INVALID_ARGUMENT"));
    }

    @Test
    void callAuditTellsAStepOneRejectionFromLaterErrors() {
        ToolCallContext cx = ctx();
        cx.attachAudit(audit, "move_messages");
        assertThat(cx.audit().argumentsRejected()).isFalse();
        cx.rejectArguments();
        assertThat(cx.audit().argumentsRejected()).isTrue();
        assertThat(cx.argumentsRejected()).isTrue();
    }
}
