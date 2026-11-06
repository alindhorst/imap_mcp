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

    @BeforeEach
    void setUp() {
        logs.start();
        logger.addAppender(logs);
        ctx = new ToolCallContext(new AccountContext(new TenantId("t1")), "req-1");
        ctx.attachAudit(log, "move_messages");
        ctx.bindAccount(new AccountId("acc-1"));
    }

    @AfterEach
    void tearDown() {
        logger.detachAppender(logs);
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
        ctx.audit().completeAborted(true);
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
        ctx.audit().completeAborted(false);
        assertThat(log.completions).singleElement().satisfies(c -> {
            assertThat(c.result()).isEqualTo("failed");
            assertThat(c.items()).containsOnly(new ItemResult("not_attempted", null));
            assertThat(c.items()).hasSize(2);
        });
    }

    @Test
    void abortedAfterTheMutationMarksItemsUnknown() {
        ctx.audit().begin(List.of(new ItemRef("INBOX", 7, 1)));
        ctx.audit().completeAborted(true);
        assertThat(log.completions).singleElement().satisfies(c -> {
            assertThat(c.result()).isEqualTo("unknown");
            assertThat(c.items()).containsExactly(new ItemResult("unknown", null));
        });
    }
}
