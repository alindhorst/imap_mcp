package de.alexanderlindhorst.imapmcp.core.tools.write;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import de.alexanderlindhorst.imapmcp.core.error.ErrorCode;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

class WriteCallLogTest {

    static final String FOLDER = "FOLDER-MARKER-Secret";
    static final String KEYWORD = "KEYWORD-MARKER";
    static final String SERVER_TEXT = "SERVERTEXT-MARKER";
    static final String HANDLE = "m1.MARKERHANDLE.sig";

    final ListAppender<ILoggingEvent> appender = new ListAppender<>();
    Logger logger;

    @BeforeEach
    void setUp() {
        logger = (Logger) LoggerFactory.getLogger("de.alexanderlindhorst.imapmcp.core.tools.write");
        logger.setLevel(Level.TRACE);
        logger.addAppender(appender);
        appender.start();
        MDC.put("req", "req-1");
        MDC.put("tenant", "aaaaaaaaaaaa");
        MDC.put("acct", "bbbbbbbbbbbb");
        MDC.put("tool", "move_messages");
    }

    @AfterEach
    void tearDown() {
        logger.detachAppender(appender);
        logger.setLevel(null);
        MDC.clear();
    }

    ILoggingEvent only() {
        assertThat(appender.list).hasSize(1);
        ILoggingEvent e = appender.list.get(0);
        assertThat(e.getLevel()).isEqualTo(Level.INFO);
        return e;
    }

    static BulkResult result(BulkResult.Kind kind, List<BulkResult.Item> items) {
        return BulkResult.of(kind, "acct", kind == BulkResult.Kind.SET_FLAGS ? null : FOLDER, items);
    }

    void assertNoSecrets(String line) {
        assertThat(line).doesNotContain("m1.", FOLDER, KEYWORD, SERVER_TEXT, HANDLE);
    }

    @Test
    void bulkSuccessLogsCountsGroupsPathOutcomeDuration() {
        BulkResult r = result(BulkResult.Kind.MOVE, List.of(
                BulkResult.Item.ok(HANDLE, "m1.new"), BulkResult.Item.alreadyInTarget("m1.b"), BulkResult.Item.alreadyGone("m1.c")));
        WriteCallLog.bulk("move_messages", r, 2, false, Duration.ofMillis(42));
        ILoggingEvent e = only();
        String m = e.getFormattedMessage();
        assertThat(m).contains("tool=move_messages", "request_id=req-1", "duration_ms=42", "groups=2", "path=move",
                "outcome=complete", "ok=1", "already_in_target=1", "already_gone=1", "not_found=0", "failed=0", "unknown=0");
        assertThat(e.getMDCPropertyMap()).containsEntry("tenant", "aaaaaaaaaaaa").containsEntry("acct", "bbbbbbbbbbbb")
                .containsEntry("req", "req-1").containsEntry("tool", "move_messages");
        assertThat(m).doesNotContain("m1.", "error_code");
    }

    @Test
    void pathPerTool() {
        BulkResult ok = result(BulkResult.Kind.MOVE, List.of(BulkResult.Item.ok("a", "b")));
        WriteCallLog.bulk("move_messages", ok, 1, true, Duration.ZERO);
        WriteCallLog.bulk("delete_messages", result(BulkResult.Kind.DELETE, List.of(BulkResult.Item.ok("a", "b"))), 1, true, Duration.ZERO);
        WriteCallLog.bulk("copy_messages", result(BulkResult.Kind.COPY, List.of(BulkResult.Item.ok("a", "b"))), 1, false, Duration.ZERO);
        WriteCallLog.bulk("set_flags", result(BulkResult.Kind.SET_FLAGS, List.of(BulkResult.Item.okFlags("a"))), 1, false, Duration.ZERO);
        List<String> lines = appender.list.stream().map(ILoggingEvent::getFormattedMessage).toList();
        assertThat(lines.get(0)).contains("path=copy_fallback");
        assertThat(lines.get(1)).contains("path=copy_fallback");
        assertThat(lines.get(2)).contains("path=copy");
        assertThat(lines.get(3)).contains("path=store");
    }

    @Test
    void outcomePartialAndUnknown() {
        WriteCallLog.bulk("copy_messages", result(BulkResult.Kind.COPY, List.of(
                BulkResult.Item.ok("a", "b"), BulkResult.Item.notFound("c"))), 1, false, Duration.ZERO);
        WriteCallLog.bulk("copy_messages", result(BulkResult.Kind.COPY, List.of(
                BulkResult.Item.notFound("c"), BulkResult.Item.unknown("d"))), 1, false, Duration.ZERO);
        assertThat(appender.list.get(0).getFormattedMessage()).contains("outcome=partial");
        assertThat(appender.list.get(1).getFormattedMessage()).contains("outcome=unknown");
    }

    @Test
    void createFolderLogsCreatedAndSubscribedOnly() {
        MDC.put("tool", "create_folder");
        WriteCallLog.createFolder("create_folder", true, false, Duration.ofMillis(7));
        String m = only().getFormattedMessage();
        assertThat(m).contains("tool=create_folder", "request_id=req-1", "created=true", "subscribed=false", "duration_ms=7");
        assertThat(m).doesNotContain("groups", "path", "outcome", "ok=", "error_code");
        assertNoSecrets(m);
    }

    @Test
    void wholeCallErrorLogsCodeWithoutCounts() {
        WriteCallLog.error("move_messages", ErrorCode.TIMEOUT, Duration.ofMillis(15000));
        String m = only().getFormattedMessage();
        assertThat(m).contains("tool=move_messages", "request_id=req-1", "error_code=TIMEOUT", "duration_ms=15000");
        assertThat(m).doesNotContain("groups", "path", "outcome", "ok=");
    }

    @Test
    void nothingSensitiveAtTraceLevel() {
        BulkResult r = BulkResult.of(BulkResult.Kind.MOVE, "acct", FOLDER, List.of(
                BulkResult.Item.ok(HANDLE, "m1.new"),
                BulkResult.Item.failed("m1.x", de.alexanderlindhorst.imapmcp.core.error.ToolError.upstreamError(SERVER_TEXT, "req-1"))));
        WriteCallLog.bulk("move_messages", r, 1, false, Duration.ZERO);
        WriteCallLog.createFolder("create_folder", true, true, Duration.ZERO);
        WriteCallLog.error("create_folder", ErrorCode.UPSTREAM_ERROR, Duration.ZERO);
        assertThat(appender.list).hasSize(3);
        appender.list.forEach(e -> assertThat(e.getLevel()).isEqualTo(Level.INFO));
        appender.list.forEach(e -> {
            assertNoSecrets(e.getFormattedMessage());
            assertNoSecrets(e.getMDCPropertyMap().toString());
            assertThat(e.getArgumentArray() == null ? "" : java.util.Arrays.toString(e.getArgumentArray())).doesNotContain("m1.", FOLDER);
        });
    }
}
