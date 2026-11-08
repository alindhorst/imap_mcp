package de.alexanderlindhorst.imapmcp.core.tool;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
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
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

/** D11 (SPEC 5.3, 5.6, v0.24): a mutating call of a principal without a tenant row is counted and warned about, never audited. */
class ToolExecutorWriteWithoutTenantTest {

    static final String PRINCIPAL = "0123456789ab";
    static final String CANARY_HANDLE = "acc_CanaryHandleZZZZZZZZZZZZZZZZ";
    final AtomicReference<Instant> now = new AtomicReference<>(Instant.parse("2026-11-08T10:00:00Z"));
    final Clock clock = new Clock() {
        @Override
        public java.time.ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(java.time.ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now.get();
        }
    };
    final LogIds logIds = new LogIds(new KeyRing(1, Map.of(1, new byte[32])));
    final InMemoryMetricsRecorder rec = new InMemoryMetricsRecorder(logIds, clock);
    final RecordingAuditLog audit = new RecordingAuditLog();
    final ToolExecutor executor = new ToolExecutor(Duration.ofSeconds(5), logIds, rec, (t, r, c, d) -> { }, audit);
    final ListAppender<ILoggingEvent> logs = new ListAppender<>();
    final Logger logger = (Logger) LoggerFactory.getLogger("de.alexanderlindhorst.imapmcp");
    Level old;

    @BeforeEach
    void setUp() {
        old = logger.getLevel();
        logger.setLevel(Level.TRACE);
        logs.start();
        logger.addAppender(logs);
    }

    @AfterEach
    void tearDown() {
        logger.detachAppender(logs);
        logger.setLevel(old);
    }

    static ToolDefinition def(String name, ToolHints hints) {
        Map<String, Object> schema = Map.of("type", "object");
        return new ToolDefinition(name, "W", "d", schema, schema, hints);
    }

    static ToolCallContext none(String rid) {
        return new ToolCallContext(new AccountContext(TenantId.NONE, null, PRINCIPAL), rid);
    }

    ToolHandler notFound() {
        return (cx, a) -> new ToolOutcome.Failure(ToolError.accountNotFound(cx.requestId()));
    }

    long count(String tool) {
        return rec.snapshot().operations().stream()
                .filter(o -> o.tool().equals(tool) && o.op().equals("write_without_tenant"))
                .mapToLong(MetricsSnapshot.OperationMetrics::count).sum();
    }

    long warns() {
        return logs.list.stream().filter(e -> e.getLevel() == Level.WARN
                && e.getFormattedMessage().contains("without a tenant row")).count();
    }

    @Test
    void eachCallIsCountedPerToolAndWritesNoAuditRow() {
        ToolDefinition move = def("move_messages", ToolHints.WRITE_DESTRUCTIVE_IDEMPOTENT);
        executor.wrap(move, notFound()).handle(none("r1"), Map.of("account", CANARY_HANDLE));
        executor.wrap(move, notFound()).handle(none("r2"), Map.of("account", CANARY_HANDLE));
        executor.wrap(def("copy_messages", ToolHints.WRITE_ADDITIVE), notFound()).handle(none("r3"), Map.of());
        assertThat(count("move_messages")).isEqualTo(2);
        assertThat(count("copy_messages")).isEqualTo(1);
        assertThat(audit.order).isEmpty();
    }

    @Test
    void internalErrorAndDeadlineDuringTheLookupAreCountedAndAuditedNowhere() {
        ToolDefinition move = def("move_messages", ToolHints.WRITE_DESTRUCTIVE_IDEMPOTENT);
        executor.wrap(move, (cx, a) -> {
            throw new IllegalStateException("lookup failed");
        }).handle(none("r1"), Map.of());
        ToolExecutor quick = new ToolExecutor(Duration.ofMillis(100), logIds, rec, (t, r, c, d) -> { }, audit);
        quick.wrap(move, (cx, a) -> {
            try {
                Thread.sleep(5_000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return new ToolOutcome.Success(Map.of());
        }).handle(none("r2"), Map.of());
        assertThat(count("move_messages")).isEqualTo(2);
        assertThat(audit.order).isEmpty();
    }

    @Test
    void theWarnIsWrittenAtMostOncePerMinutePerReplicaAndCarriesNoHandle() {
        ToolDefinition move = def("move_messages", ToolHints.WRITE_DESTRUCTIVE_IDEMPOTENT);
        for (int i = 0; i < 3; i++) {
            executor.wrap(move, notFound()).handle(none("req-" + i), Map.of("account", CANARY_HANDLE));
            now.updateAndGet(t -> t.plusSeconds(10));
        }
        assertThat(warns()).isEqualTo(1);
        assertThat(count("move_messages")).isEqualTo(3);
        now.updateAndGet(t -> t.plusSeconds(31)); // 61 s since the first WARN
        executor.wrap(move, notFound()).handle(none("req-late"), Map.of());
        assertThat(warns()).isEqualTo(2);
        ILoggingEvent first = logs.list.stream().filter(e -> e.getLevel() == Level.WARN
                && e.getFormattedMessage().contains("without a tenant row")).findFirst().orElseThrow();
        assertThat(first.getFormattedMessage()).contains("move_messages").contains("req-0")
                .contains("ACCOUNT_NOT_FOUND").contains(PRINCIPAL);
        assertThat(logs.list.stream().map(ILoggingEvent::getFormattedMessage).toList().toString())
                .doesNotContain(CANARY_HANDLE);
    }

    @Test
    void aStepOneRejectionStaysD10WithoutTheNewMetric() {
        ToolDefinition move = def("move_messages", ToolHints.WRITE_DESTRUCTIVE_IDEMPOTENT);
        ToolOutcome out = executor.wrap(move, (cx, a) -> {
            cx.rejectArguments();
            return new ToolOutcome.Failure(ToolError.invalidArgument("handles", "out_of_range", 100, "r1"));
        }).handle(none("r1"), Map.of());
        assertThat(out).isInstanceOf(ToolOutcome.Failure.class);
        assertThat(count("move_messages")).isZero();
        assertThat(warns()).isZero();
        assertThat(rec.snapshot().operations()).anyMatch(o -> o.op().equals("write_argument_rejected"));
    }

    @Test
    void realTenantsAndReadToolsAreNotCounted() {
        ToolCallContext real = new ToolCallContext(new AccountContext(new TenantId("real")), "r1");
        executor.wrap(def("move_messages", ToolHints.WRITE_DESTRUCTIVE_IDEMPOTENT), notFound()).handle(real, Map.of());
        executor.wrap(def("get_message", ToolHints.READ_ONLY_OPEN_WORLD), notFound()).handle(none("r2"), Map.of());
        assertThat(count("move_messages")).isZero();
        assertThat(count("get_message")).isZero();
        assertThat(warns()).isZero();
    }

    @Test
    void withoutALogIdTheWarnStillNamesNoPrincipalValue() {
        ToolCallContext bare = new ToolCallContext(new AccountContext(TenantId.NONE), "r1");
        executor.wrap(def("move_messages", ToolHints.WRITE_DESTRUCTIVE_IDEMPOTENT), notFound()).handle(bare, Map.of());
        assertThat(count("move_messages")).isEqualTo(1);
        assertThat(warns()).isEqualTo(1);
    }
}
