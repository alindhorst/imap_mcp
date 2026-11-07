package de.alexanderlindhorst.imapmcp.core.tool;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.alexanderlindhorst.imapmcp.core.error.ErrorCode;
import de.alexanderlindhorst.imapmcp.core.tenancy.AccountContext;
import de.alexanderlindhorst.imapmcp.core.tenancy.TenantId;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class ValidatingToolHandlerTest {

    static final ToolDefinition DEF = new ToolDefinition("t", "T", "Do things.", InputValidatorTest.sample(),
            Map.of("type", "object"), ToolHints.READ_ONLY_OPEN_WORLD);
    final ToolHandler delegate = mock(ToolHandler.class);
    final ToolCallContext ctx = new ToolCallContext(new AccountContext(new TenantId("t1")), "req-9");
    final ToolHandler h = new ValidatingToolHandler(DEF, delegate);

    @Test
    void delegateNeverCalledOnInvalidInput() {
        ToolOutcome o = h.handle(ctx, Map.of("account", "a", "page_size", 101));
        assertThat(o).isInstanceOf(ToolOutcome.Failure.class);
        ToolOutcome.Failure f = (ToolOutcome.Failure) o;
        assertThat(f.error().code()).isEqualTo(ErrorCode.INVALID_ARGUMENT);
        assertThat(f.error().requestId()).isEqualTo("req-9");
        verify(delegate, never()).handle(any(), any());
    }

    @Test
    @SuppressWarnings("unchecked")
    void delegateSeesArgsWithoutNulls() {
        ToolOutcome ok = new ToolOutcome.Success(Map.of());
        when(delegate.handle(any(), any())).thenReturn(ok);
        Map<String, Object> a = new HashMap<>();
        a.put("account", "acc_x");
        a.put("cursor", null);
        assertThat(h.handle(ctx, a)).isSameAs(ok);
        ArgumentCaptor<Map<String, Object>> cap = ArgumentCaptor.forClass(Map.class);
        verify(delegate).handle(any(), cap.capture());
        assertThat(cap.getValue()).containsOnlyKeys("account");
    }

    @Test
    void aSchemaFailureMarksTheCallAsAStepOneRejection() {
        h.handle(ctx, Map.of("account", "a", "page_size", 101));
        assertThat(ctx.argumentsRejected()).isTrue();
        ToolCallContext fine = new ToolCallContext(new AccountContext(new TenantId("t1")), "req-10");
        when(delegate.handle(any(), any())).thenReturn(new ToolOutcome.Success(Map.of()));
        h.handle(fine, Map.of("account", "a"));
        assertThat(fine.argumentsRejected()).isFalse();
    }
}
