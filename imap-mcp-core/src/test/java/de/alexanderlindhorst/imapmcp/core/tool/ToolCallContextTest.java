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
}
