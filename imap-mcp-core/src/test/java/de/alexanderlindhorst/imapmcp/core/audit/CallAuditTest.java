package de.alexanderlindhorst.imapmcp.core.audit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import de.alexanderlindhorst.imapmcp.core.tenancy.AccountContext;
import de.alexanderlindhorst.imapmcp.core.tenancy.AccountId;
import de.alexanderlindhorst.imapmcp.core.tenancy.TenantId;
import de.alexanderlindhorst.imapmcp.core.tool.ToolCallContext;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

class CallAuditTest {

    final RecordingAuditLog log = new RecordingAuditLog();
    final ListAppender<ILoggingEvent> logs = new ListAppender<>();
    final Logger logger = (Logger) LoggerFactory.getLogger(CallAudit.class);
    ToolCallContext ctx;
    Level oldLevel;

    @BeforeEach
    void setUp() {
        oldLevel = logger.getLevel();
        logger.setLevel(Level.TRACE);
        logs.start();
        logger.addAppender(logs);
        ctx = new ToolCallContext(new AccountContext(new TenantId("t1")), "req-1");
        ctx.attachAudit(log, "move_messages");
        ctx.bindAccount(new AccountId("acc-1"));
    }

    @AfterEach
    void tearDown() {
        logger.detachAppender(logs);
        logger.setLevel(oldLevel);
    }

    @Test
    void withoutAnAttachedLogEverythingIsANoOp() {
        ToolCallContext plain = new ToolCallContext(new AccountContext(new TenantId("t1")), "r");
        assertThat(plain.audit().enabled()).isFalse();
        plain.audit().begin(List.of(new ItemRef("INBOX", 1, 1)));
        plain.audit().complete(List.of(), "complete", null);
        assertThat(log.order).isEmpty();
    }

    @Test
    void beginPassesCallIdentityFromTheContextNotFromTheCaller() {
        ctx.audit().begin(List.of(new ItemRef("INBOX", 7, 1), new ItemRef("INBOX", 7, 2)));
        assertThat(log.calls).singleElement().satisfies(c -> {
            assertThat(c.tenant()).isEqualTo(new TenantId("t1"));
            assertThat(c.account()).isEqualTo(new AccountId("acc-1"));
            assertThat(c.requestId()).isEqualTo("req-1");
            assertThat(c.tool()).isEqualTo("move_messages");
        });
        assertThat(log.begun.get(0)).hasSize(2);
    }

    @Test
    void beginIsOncePerCall() {
        ctx.audit().begin(List.of(new ItemRef("INBOX", 7, 1)));
        ctx.audit().begin(List.of(new ItemRef("INBOX", 7, 2)));
        assertThat(log.order).containsExactly("begin");
    }

    @Test
    void failedBeginPropagatesAndLeavesNothingToComplete() {
        log.failBegin = true;
        assertThatThrownBy(() -> ctx.audit().begin(List.of())).isInstanceOf(AuditWriteException.class);
        ctx.audit().complete(List.of(), "complete", null);
        assertThat(log.order).isEmpty();
    }

    @Test
    void completeCarriesResultsAndTheConfirmation() {
        ctx.audit().begin(List.of(new ItemRef("Trash", 7, 1)));
        ctx.audit().confirmation("elicited");
        ctx.audit().complete(List.of(new ItemResult("ok", null)), "complete", null);
        assertThat(log.completions).singleElement().satisfies(c -> {
            assertThat(c.items()).containsExactly(new ItemResult("ok", null));
            assertThat(c.result()).isEqualTo("complete");
            assertThat(c.confirmation()).isEqualTo("elicited");
        });
    }

    @Test
    void completeRunsOnlyOnce() {
        ctx.audit().begin(List.of());
        ctx.audit().complete(List.of(), "complete", null);
        ctx.audit().completeAborted(true, "TIMEOUT");
        assertThat(log.order).containsExactly("begin", "complete");
    }

    @Test
    void failedCompletionIsAnErrorLineWithCountsOnlyAndDoesNotThrow() {
        ctx.audit().begin(List.of(new ItemRef("secret-folder", 7, 1), new ItemRef("secret-folder", 7, 2)));
        log.failComplete = true;
        ctx.audit().complete(List.of(new ItemResult("ok", null), new ItemResult("failed", "UPSTREAM_ERROR")),
                "partial", null);
        assertThat(logs.list).singleElement().satisfies(e -> {
            assertThat(e.getLevel()).isEqualTo(Level.ERROR);
            String m = e.getFormattedMessage();
            assertThat(m).contains("move_messages").contains("req-1").contains("ok=1").contains("failed=1");
            assertThat(m).doesNotContain("secret-folder").doesNotContain("acc-1").doesNotContain("t1");
            assertThat(e.getThrowableProxy()).isNull();
        });
    }

    @Test
    void abortedBeforeTheMutationMarksItemsNotAttempted() {
        ctx.audit().begin(List.of(new ItemRef("INBOX", 7, 1), new ItemRef("INBOX", 7, 2)));
        ctx.audit().completeAborted(false, "UPSTREAM_ERROR");
        assertThat(log.completions).singleElement().satisfies(c -> {
            assertThat(c.errorCode()).isEqualTo("UPSTREAM_ERROR");
            assertThat(c.result()).isEqualTo("failed");
            assertThat(c.items()).containsOnly(new ItemResult("not_attempted", "UPSTREAM_ERROR"));
            assertThat(c.items()).hasSize(2);
        });
    }

    @Test
    void abortedAfterTheMutationMarksItemsUnknown() {
        ctx.audit().begin(List.of(new ItemRef("INBOX", 7, 1)));
        ctx.audit().completeAborted(true, "OUTCOME_UNKNOWN");
        assertThat(log.completions).singleElement().satisfies(c -> {
            assertThat(c.errorCode()).isEqualTo("OUTCOME_UNKNOWN");
            assertThat(c.result()).isEqualTo("unknown");
            assertThat(c.items()).containsExactly(new ItemResult("unknown", "OUTCOME_UNKNOWN"));
        });
    }

    @Test
    void theConfirmationIsWrittenWithTheRowsAtBegin() {
        ctx.audit().confirmation("session_approval");
        ctx.audit().begin(List.of(new ItemRef("Trash", 7, 1)));
        assertThat(log.calls.get(0).confirmation()).isEqualTo("session_approval");
    }

    @Test
    void recordFailureWritesOneItemLessFailedRow() {
        ctx.audit().recordFailure("INVALID_HANDLE");
        assertThat(log.order).containsExactly("begin", "complete");
        assertThat(log.begun.get(0)).isEmpty();
        assertThat(log.completions.get(0).result()).isEqualTo("failed");
        assertThat(log.completions.get(0).errorCode()).isEqualTo("INVALID_HANDLE");
        assertThat(log.completions.get(0).items()).isEmpty();
    }

    @Test
    void recordFailureWritesAnAccountlessCallRowForARealTenant() {
        ToolCallContext noAccount = new ToolCallContext(new AccountContext(new TenantId("t1")), "req-3");
        noAccount.attachAudit(log, "move_messages");
        noAccount.audit().recordFailure("ACCOUNT_NOT_FOUND");
        assertThat(log.order).containsExactly("begin", "complete");
        assertThat(log.calls.get(0).account()).isNull();
        assertThat(log.calls.get(0).tenant()).isEqualTo(new TenantId("t1"));
    }

    @Test
    void recordFailureWritesNoRowForAPrincipalWithoutATenantRow() {
        ToolCallContext none = new ToolCallContext(new AccountContext(TenantId.NONE), "req-4");
        none.attachAudit(log, "move_messages");
        none.audit().recordFailure("ACCOUNT_NOT_FOUND");
        assertThat(log.order).isEmpty();
    }

    @Test
    void recordFailureDoesNothingOnceRowsWereBegunOrAttempted() {
        ctx.audit().begin(List.of(new ItemRef("INBOX", 7, 1)));
        ctx.audit().recordFailure("INVALID_HANDLE");
        assertThat(log.order).containsExactly("begin");

        ToolCallContext other = new ToolCallContext(new AccountContext(new TenantId("t1")), "req-2");
        RecordingAuditLog failing = new RecordingAuditLog();
        failing.failBegin = true;
        other.attachAudit(failing, "move_messages");
        assertThatThrownBy(() -> other.audit().begin(List.of())).isInstanceOf(AuditWriteException.class);
        failing.failBegin = false;
        other.audit().recordFailure("INTERNAL_ERROR");
        assertThat(failing.order).isEmpty();
    }

    /** The executor's deadline row is written by recordFailure; a late worker's begin must not add item rows. */
    @Test
    void beginIsANoOpAfterRecordFailure() {
        ctx.audit().recordFailure("TIMEOUT");
        ctx.audit().begin(List.of(new ItemRef("INBOX", 7, 1), new ItemRef("INBOX", 7, 2)));
        assertThat(log.order).containsExactly("begin", "complete");
        assertThat(log.begun).singleElement().satisfies(items -> assertThat(items).isEmpty());
    }

    @Test
    void recordFailureNeverThrowsAndLogsOneErrorLineWithoutRowData() {
        log.failBegin = true;
        ctx.audit().recordFailure("INVALID_HANDLE");
        assertThat(logs.list).singleElement().satisfies(e -> {
            assertThat(e.getLevel()).isEqualTo(Level.ERROR);
            assertThat(e.getFormattedMessage()).contains("move_messages").contains("req-1")
                    .doesNotContain("acc-1").doesNotContain("t1");
            assertThat(e.getThrowableProxy()).isNull();
        });
    }

    @Test
    void recordRefusalWritesOneRowPerItemWithTheCodeAndConfirmation() {
        ctx.audit().recordRefusal(List.of(new ItemRef("Trash", 7, 1), new ItemRef("Trash", 7, 2)),
                "PERMISSION_DENIED", "declined");
        assertThat(log.order).containsExactly("begin", "complete");
        assertThat(log.begun.get(0)).hasSize(2);
        assertThat(log.calls.get(0).confirmation()).isEqualTo("declined");
        assertThat(log.completions.get(0)).satisfies(c -> {
            assertThat(c.items()).containsExactly(new ItemResult(null, null), new ItemResult(null, null));
            assertThat(c.result()).isEqualTo("failed");
            assertThat(c.errorCode()).isEqualTo("PERMISSION_DENIED");
        });
    }

    @Test
    void recordRefusalIsNotFailClosed() {
        log.failBegin = true;
        ctx.audit().recordRefusal(List.of(new ItemRef("Trash", 7, 1)), "PERMISSION_DENIED", "declined");
        assertThat(logs.list).singleElement().satisfies(e -> assertThat(e.getLevel()).isEqualTo(Level.ERROR));
    }
}
