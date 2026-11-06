package de.alexanderlindhorst.imapmcp.it;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import de.alexanderlindhorst.imapmcp.core.audit.AuditLog;
import de.alexanderlindhorst.imapmcp.core.audit.AuditReferences;
import de.alexanderlindhorst.imapmcp.core.audit.AuditWriteException;
import de.alexanderlindhorst.imapmcp.core.audit.CallInfo;
import de.alexanderlindhorst.imapmcp.core.audit.Completion;
import de.alexanderlindhorst.imapmcp.core.audit.EventInfo;
import de.alexanderlindhorst.imapmcp.core.audit.ItemRef;
import de.alexanderlindhorst.imapmcp.core.audit.ItemResult;
import de.alexanderlindhorst.imapmcp.core.crypto.KeyRing;
import de.alexanderlindhorst.imapmcp.core.crypto.LogIds;
import de.alexanderlindhorst.imapmcp.core.error.ErrorCode;
import de.alexanderlindhorst.imapmcp.core.metrics.MetricsRecorder;
import de.alexanderlindhorst.imapmcp.core.tenancy.AccountContext;
import de.alexanderlindhorst.imapmcp.core.tenancy.AccountId;
import de.alexanderlindhorst.imapmcp.core.tenancy.TenantId;
import de.alexanderlindhorst.imapmcp.core.tool.ToolCallContext;
import de.alexanderlindhorst.imapmcp.core.tool.ToolExecutor;
import de.alexanderlindhorst.imapmcp.core.tool.ToolHints;
import de.alexanderlindhorst.imapmcp.core.tool.ToolDefinition;
import de.alexanderlindhorst.imapmcp.core.tool.ToolOutcome;
import de.alexanderlindhorst.imapmcp.store.jdbc.AuditRetention;
import de.alexanderlindhorst.imapmcp.store.jdbc.Database;
import de.alexanderlindhorst.imapmcp.store.jdbc.JdbcAuditLog;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** A9: the audit writer against Postgres (SPEC 5.3, D5): hashes only, fail-closed, completion, events, retention. */
class AuditLogIT {

    private static PostgresFixture postgres;
    private static Database db;
    private static final byte[] K1 = new byte[32];
    private static final byte[] K2 = new byte[32];
    private static final KeyRing RING = new KeyRing(2, Map.of(1, K1, 2, K2));

    private final TenantId tenant = new TenantId(UUID.randomUUID().toString());
    private final AccountId account = new AccountId("acc-" + UUID.randomUUID().toString().substring(0, 8));
    private final JdbcAuditLog log = new JdbcAuditLog(db.dataSource(), RING);

    @BeforeAll
    static void start() {
        Arrays.fill(K1, (byte) 1);
        Arrays.fill(K2, (byte) 2);
        postgres = new PostgresFixture();
        postgres.start();
        db = Database.open(postgres.config());
    }

    @AfterAll
    static void stop() {
        db.close();
        postgres.stop();
    }

    @BeforeEach
    void clean() throws SQLException {
        try (Connection c = db.dataSource().getConnection(); var st = c.createStatement()) {
            st.execute("DELETE FROM audit_event");
        }
    }

    private CallInfo call(String requestId) {
        return new CallInfo(tenant, account, requestId, "purge_messages");
    }

    private record Row(long id, String tenantId, String accountId, String requestId, String eventType, String tool,
            byte[] refHash, Integer refKeyId, String itemStatus, String errorCode, String confirmation, String result,
            Instant completedAt, Instant occurredAt) {
    }

    private List<Row> rows() throws SQLException {
        List<Row> out = new ArrayList<>();
        try (Connection c = db.dataSource().getConnection();
                PreparedStatement ps = c.prepareStatement("SELECT id, tenant_id::text, account_id, request_id, event_type,"
                        + " tool, ref_hash, ref_key_id, item_status, error_code, confirmation, result, completed_at,"
                        + " occurred_at FROM audit_event ORDER BY id");
                ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                out.add(new Row(rs.getLong(1), rs.getString(2), rs.getString(3), rs.getString(4), rs.getString(5),
                        rs.getString(6), rs.getBytes(7), (Integer) rs.getObject(8), rs.getString(9), rs.getString(10),
                        rs.getString(11), rs.getString(12),
                        rs.getTimestamp(13) == null ? null : rs.getTimestamp(13).toInstant(),
                        rs.getTimestamp(14).toInstant()));
            }
        }
        return out;
    }

    @Test
    void beginWritesOneHashedPendingRowPerItem() throws Exception {
        List<ItemRef> items = List.of(new ItemRef("INBOX", 7, 1), new ItemRef("INBOX", 7, 2),
                new ItemRef("Trash", 9, 5));
        log.begin(call("req-1"), items);
        List<Row> rows = rows();
        assertThat(rows).hasSize(3);
        AuditReferences refs = new AuditReferences(RING);
        for (int i = 0; i < 3; i++) {
            Row r = rows.get(i);
            assertThat(r.tenantId()).isEqualTo(tenant.value());
            assertThat(r.accountId()).isEqualTo(account.value());
            assertThat(r.requestId()).isEqualTo("req-1");
            assertThat(r.eventType()).isEqualTo("tool_call");
            assertThat(r.tool()).isEqualTo("purge_messages");
            assertThat(r.itemStatus()).isEqualTo("pending");
            assertThat(r.completedAt()).isNull();
            AuditReferences.Ref expected = refs.ref(tenant, account, items.get(i));
            assertThat(r.refHash()).isEqualTo(expected.hash());
            assertThat(r.refKeyId()).isEqualTo(2);
        }
    }

    @Test
    void completeFillsStatusErrorConfirmationAndTime() throws Exception {
        AuditLog.Entry entry = log.begin(call("req-2"), List.of(new ItemRef("Trash", 9, 1), new ItemRef("Trash", 9, 2)));
        entry.complete(new Completion(List.of(new ItemResult("ok", null), new ItemResult("failed", "UPSTREAM_ERROR")),
                "partial", null, "elicited"));
        List<Row> rows = rows();
        assertThat(rows).extracting(Row::itemStatus).containsExactly("ok", "failed");
        assertThat(rows).extracting(Row::errorCode).containsExactly(null, "UPSTREAM_ERROR");
        assertThat(rows).extracting(Row::confirmation).containsOnly("elicited");
        assertThat(rows).extracting(Row::result).containsOnly("partial");
        assertThat(rows).allSatisfy(r -> assertThat(r.completedAt()).isNotNull());
    }

    @Test
    void aCallWithoutItemsIsOneRowWithoutHash() throws Exception {
        AuditLog.Entry entry = log.begin(new CallInfo(tenant, account, "req-3", "create_folder"), List.of());
        entry.complete(new Completion(List.of(), "complete", null, null));
        List<Row> rows = rows();
        assertThat(rows).singleElement().satisfies(r -> {
            assertThat(r.refHash()).isNull();
            assertThat(r.refKeyId()).isNull();
            assertThat(r.tool()).isEqualTo("create_folder");
            assertThat(r.result()).isEqualTo("complete");
            assertThat(r.completedAt()).isNotNull();
        });
    }

    @Test
    void wholeCallErrorIsRecorded() throws Exception {
        AuditLog.Entry entry = log.begin(new CallInfo(tenant, account, "req-4", "create_folder"), List.of());
        entry.complete(new Completion(List.of(), "failed", "PERMISSION_DENIED", null));
        assertThat(rows()).singleElement().satisfies(r -> {
            assertThat(r.errorCode()).isEqualTo("PERMISSION_DENIED");
            assertThat(r.result()).isEqualTo("failed");
        });
    }

    @Test
    void theTableNeverHoldsFolderNamesUidsHandlesOrAddresses() throws Exception {
        log.begin(call("req-5"), List.of(new ItemRef("CANARY-folder-ä", 4242424, 9191919))).complete(
                new Completion(List.of(new ItemResult("ok", null)), "complete", null, "elicited"));
        log.event(new EventInfo("link", tenant, account, "req-6", "ok"));
        StringBuilder dump = new StringBuilder();
        try (Connection c = db.dataSource().getConnection();
                PreparedStatement ps = c.prepareStatement("SELECT row_to_json(a)::text, encode(ref_hash, 'hex')"
                        + " FROM audit_event a");
                ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                dump.append(rs.getString(1)).append(rs.getString(2));
            }
        }
        assertThat(dump.toString()).doesNotContain("CANARY").doesNotContain("4242424").doesNotContain("9191919")
                .doesNotContain("INBOX").doesNotContain("m1.");
        assertThat(HexFormat.of().formatHex(new AuditReferences(RING).ref(tenant, account,
                new ItemRef("CANARY-folder-ä", 4242424, 9191919)).hash())).isNotEmpty();
    }

    private String dump() throws SQLException {
        StringBuilder dump = new StringBuilder();
        try (Connection c = db.dataSource().getConnection();
                PreparedStatement ps = c.prepareStatement("SELECT row_to_json(a)::text, encode(ref_hash, 'hex')"
                        + " FROM audit_event a");
                ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                dump.append(rs.getString(1)).append(rs.getString(2));
            }
        }
        return dump.toString();
    }

    private ToolCallContext audited(String requestId, String tool) {
        ToolCallContext ctx = new ToolCallContext(new AccountContext(tenant), requestId);
        ctx.attachAudit(log, tool);
        ctx.bindAccount(account);
        return ctx;
    }

    @Test
    void theConfirmationIsStoredWithTheRowsBeforeCompletion() throws Exception {
        ToolCallContext ctx = audited("req-c", "purge_messages");
        ctx.audit().confirmation("session_approval");
        ctx.audit().begin(List.of(new ItemRef("Trash", 3, 3)));
        assertThat(rows()).singleElement().satisfies(r -> {
            assertThat(r.confirmation()).isEqualTo("session_approval");
            assertThat(r.completedAt()).isNull();
        });
    }

    @Test
    void aRefusedPurgeIsOneRowPerVerifiedItemWithTheCodeAndTheElicitationValue() throws Exception {
        audited("req-r", "purge_messages").audit().recordRefusal(
                List.of(new ItemRef("CANARY-trash", 5151515, 6161616), new ItemRef("CANARY-trash", 5151515, 6161617)),
                "PERMISSION_DENIED", "declined");
        List<Row> rows = rows();
        assertThat(rows).hasSize(2).allSatisfy(r -> {
            assertThat(r.errorCode()).isEqualTo("PERMISSION_DENIED");
            assertThat(r.confirmation()).isEqualTo("declined");
            assertThat(r.itemStatus()).isEqualTo("not_attempted");
            assertThat(r.result()).isEqualTo("failed");
            assertThat(r.refHash()).hasSize(16);
        });
        assertThat(dump()).doesNotContain("CANARY").doesNotContain("5151515").doesNotContain("6161616");
    }

    @Test
    void itemsAlreadyInTheTargetCarryTheirStatusNextToTheMovedOnes() throws Exception {
        ToolCallContext ctx = audited("req-m", "move_messages");
        ctx.audit().begin(List.of(new ItemRef("INBOX", 1, 1), new ItemRef("Target", 1, 9)));
        ctx.audit().complete(List.of(new ItemResult("ok", null), new ItemResult("already_in_target", null)),
                "complete", null);
        assertThat(rows()).extracting(Row::itemStatus).containsExactly("ok", "already_in_target");
    }

    @Test
    void aWholeCallErrorWithoutRowsBecomesOneItemLessCallRowThroughTheExecutor() throws Exception {
        ToolExecutor executor = new ToolExecutor(Duration.ofSeconds(5), new LogIds(RING), MetricsRecorder.NOOP,
                (t, r, c, d) -> { }, log);
        Map<String, Object> schema = Map.of("type", "object");
        ToolDefinition def = new ToolDefinition("move_messages", "M", "d", schema, schema,
                ToolHints.WRITE_DESTRUCTIVE_IDEMPOTENT);
        ToolOutcome out = executor.wrap(def, (ctx, a) -> new ToolOutcome.Failure(
                de.alexanderlindhorst.imapmcp.core.error.ToolError.invalidHandle("handles[0]", "req-w")))
                .handle(new ToolCallContext(new AccountContext(tenant), "req-w"), Map.of());
        assertThat(out).isInstanceOf(ToolOutcome.Failure.class);
        assertThat(rows()).singleElement().satisfies(r -> {
            assertThat(r.tool()).isEqualTo("move_messages");
            assertThat(r.requestId()).isEqualTo("req-w");
            assertThat(r.accountId()).isNull();
            assertThat(r.refHash()).isNull();
            assertThat(r.errorCode()).isEqualTo("INVALID_HANDLE");
            assertThat(r.result()).isEqualTo("failed");
            assertThat(r.completedAt()).isNotNull();
        });
    }

    @Test
    void linkingEventsHoldOnlyTypeTenantAccountRequestAndResult() throws Exception {
        log.event(new EventInfo("link", tenant, account, "req-7", "ok"));
        log.event(new EventInfo("tenant_created", tenant, null, "req-8", "ok"));
        List<Row> rows = rows();
        assertThat(rows).hasSize(2);
        assertThat(rows.get(0)).satisfies(r -> {
            assertThat(r.eventType()).isEqualTo("link");
            assertThat(r.tenantId()).isEqualTo(tenant.value());
            assertThat(r.accountId()).isEqualTo(account.value());
            assertThat(r.requestId()).isEqualTo("req-7");
            assertThat(r.result()).isEqualTo("ok");
            assertThat(r.tool()).isNull();
            assertThat(r.refHash()).isNull();
            assertThat(r.completedAt()).isNotNull();
        });
        assertThat(rows.get(1).accountId()).isNull();
    }

    @Test
    void aFailedWriteIsAnAuditWriteExceptionWithoutRowDataAndLeavesNoPartialRows() throws Exception {
        CallInfo bad = new CallInfo(new TenantId("not-a-uuid"), account, "req-9", "purge_messages");
        assertThatThrownBy(() -> log.begin(bad, List.of(new ItemRef("CANARY", 1, 1))))
                .isInstanceOf(AuditWriteException.class).hasMessageNotContaining("CANARY").hasNoCause();
        assertThat(rows()).isEmpty();
        assertThatThrownBy(() -> log.event(new EventInfo("link", new TenantId("not-a-uuid"), account, "r", "ok")))
                .isInstanceOf(AuditWriteException.class);
    }

    @Test
    void anUnavailableDatabaseFailsTheMutatingCallBeforeItMutatesAnything() {
        Database other = Database.open(postgres.config());
        JdbcAuditLog broken = new JdbcAuditLog(other.dataSource(), RING);
        other.close();
        ToolExecutor executor = new ToolExecutor(Duration.ofSeconds(5), new LogIds(RING), MetricsRecorder.NOOP,
                (t, r, c, d) -> { }, broken);
        Map<String, Object> schema = Map.of("type", "object");
        ToolDefinition def = new ToolDefinition("purge_messages", "P", "d", schema, schema,
                ToolHints.WRITE_DESTRUCTIVE_IDEMPOTENT);
        AtomicBoolean mutated = new AtomicBoolean();
        ToolOutcome out = executor.wrap(def, (ctx, a) -> {
            ctx.audit().begin(List.of(new ItemRef("Trash", 1, 1)));
            mutated.set(ctx.tryBeginMutation());
            return new ToolOutcome.Success(Map.of());
        }).handle(new ToolCallContext(new AccountContext(tenant), "req-10"), Map.of());
        assertThat(mutated).isFalse();
        assertThat(out).isInstanceOfSatisfying(ToolOutcome.Failure.class,
                f -> assertThat(f.error().code()).isEqualTo(ErrorCode.INTERNAL_ERROR));
    }

    @Test
    void retentionDeletesRowsOlderThan180DaysAndKeepsTheRest() throws Exception {
        log.begin(call("old"), List.of(new ItemRef("Trash", 1, 1)));
        log.begin(call("recent"), List.of(new ItemRef("Trash", 1, 2)));
        log.begin(call("edge"), List.of(new ItemRef("Trash", 1, 3)));
        age("old", Duration.ofDays(181));
        age("edge", Duration.ofDays(179));
        AuditRetention retention = new AuditRetention(db.dataSource());
        assertThat(retention.deleteExpired(Instant.now())).isEqualTo(1);
        assertThat(rows()).extracting(Row::requestId).containsExactlyInAnyOrder("recent", "edge");
    }

    @Test
    void retentionRunsDailyOnTheGivenExecutor() throws Exception {
        var executor = java.util.concurrent.Executors.newSingleThreadScheduledExecutor();
        try {
            var future = new AuditRetention(db.dataSource()).schedule(executor);
            assertThat(future.getDelay(java.util.concurrent.TimeUnit.HOURS)).isBetween(0L, 24L);
            assertThat(future.isCancelled()).isFalse();
            future.cancel(false);
        } finally {
            executor.shutdownNow();
        }
    }

    private void age(String requestId, Duration by) throws SQLException {
        try (Connection c = db.dataSource().getConnection();
                PreparedStatement ps = c.prepareStatement(
                        "UPDATE audit_event SET occurred_at = now() - make_interval(secs => ?) WHERE request_id = ?")) {
            ps.setDouble(1, by.toSeconds());
            ps.setString(2, requestId);
            ps.executeUpdate();
        }
    }
}
