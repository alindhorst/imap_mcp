package de.alexanderlindhorst.imapmcp.core.tool;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import de.alexanderlindhorst.imapmcp.core.tenancy.AccountContext;
import de.alexanderlindhorst.imapmcp.core.tenancy.TenantId;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class ToolCallContextTest {

    private static ToolCallContext ctx() {
        return new ToolCallContext(new AccountContext(new TenantId("t1")), "req-1");
    }

    @Test
    void carriesIdentityAndRequestId() {
        ToolCallContext c = ctx();
        assertThat(c.accountContext().tenantId()).isEqualTo(new TenantId("t1"));
        assertThat(c.requestId()).isEqualTo("req-1");
        assertThat(c.isCancelled()).isFalse();
    }

    @Test
    void cancelRunsRegisteredCallbacksOnce() {
        ToolCallContext c = ctx();
        AtomicInteger n = new AtomicInteger();
        c.onCancel(n::incrementAndGet);
        c.cancel();
        c.cancel();
        assertThat(c.isCancelled()).isTrue();
        assertThat(n).hasValue(1);
    }

    @Test
    void callbackRegisteredAfterCancelRunsImmediately() {
        ToolCallContext c = ctx();
        c.cancel();
        AtomicInteger n = new AtomicInteger();
        c.onCancel(n::incrementAndGet);
        assertThat(n).hasValue(1);
    }

    @Test
    void throwingCallbackDoesNotStopOthers() {
        ToolCallContext c = ctx();
        AtomicInteger n = new AtomicInteger();
        c.onCancel(() -> { throw new IllegalStateException("x"); });
        c.onCancel(n::incrementAndGet);
        c.cancel();
        assertThat(n).hasValue(1);
    }

    @Test
    void rejectsNulls() {
        assertThatThrownBy(() -> new ToolCallContext(null, "r")).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new ToolCallContext(new AccountContext(new TenantId("t")), null))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void deadlineIsAbsentUnlessGiven() {
        assertThat(ctx().deadline()).isEmpty();
        java.time.Instant d = java.time.Instant.parse("2026-01-01T00:00:00Z");
        ToolCallContext c = new ToolCallContext(new AccountContext(new TenantId("t1")), "r", d);
        assertThat(c.deadline()).contains(d);
    }

    @Test
    void mutationStartedIsFalseUntilMarkedAndMarkIsIdempotent() {
        ToolCallContext c = ctx();
        assertThat(c.mutationStarted()).isFalse();
        assertThat(c.tryBeginMutation()).isTrue();
        assertThat(c.tryBeginMutation()).isTrue();
        assertThat(c.mutationStarted()).isTrue();
    }

    @Test
    void mutationMarkSetOnOneThreadIsVisibleOnAnother() throws Exception {
        ToolCallContext c = ctx();
        Thread t = Thread.ofVirtual().start(c::tryBeginMutation);
        t.join();
        assertThat(c.mutationStarted()).isTrue();
    }

    @Test
    void tryBeginMutationIsFalseOnceCancelledAndNeverSetsTheMark() {
        ToolCallContext c = ctx();
        c.cancel();
        assertThat(c.tryBeginMutation()).isFalse();
        assertThat(c.mutationStarted()).isFalse();
    }

    @Test
    void tryBeginMutationIsFalseOnceTheOutcomeIsDecidedAndTheDecisionIsFrozen() {
        ToolCallContext c = ctx();
        assertThat(c.sealMutation()).isFalse();
        assertThat(c.tryBeginMutation()).isFalse();
        assertThat(c.mutationStarted()).isFalse();
    }

    @Test
    void aMutationStartedBeforeTheDecisionIsReportedAndLaterCommandsAreRefused() {
        ToolCallContext c = ctx();
        assertThat(c.tryBeginMutation()).isTrue();
        assertThat(c.sealMutation()).isTrue();
        assertThat(c.tryBeginMutation()).isFalse();
        assertThat(c.mutationStarted()).isTrue();
    }

    @Test
    void mutationHooksRunOnceOnTheFirstSuccessfulBeginAndNeverAfterACancel() {
        ToolCallContext c = new ToolCallContext(new AccountContext(new TenantId("t")), "r");
        java.util.concurrent.atomic.AtomicInteger runs = new java.util.concurrent.atomic.AtomicInteger();
        c.onMutationBegun(runs::incrementAndGet);
        c.onMutationBegun(() -> {
            throw new IllegalStateException("a failing hook must not break the others");
        });
        c.onMutationBegun(runs::incrementAndGet);
        assertThat(runs).hasValue(0);
        assertThat(c.tryBeginMutation()).isTrue();
        assertThat(runs).hasValue(2);
        assertThat(c.tryBeginMutation()).isTrue();
        assertThat(runs).hasValue(2);

        ToolCallContext cancelled = new ToolCallContext(new AccountContext(new TenantId("t")), "r2");
        cancelled.onMutationBegun(runs::incrementAndGet);
        cancelled.cancel();
        assertThat(cancelled.tryBeginMutation()).isFalse();
        assertThat(runs).hasValue(2);
    }
}
