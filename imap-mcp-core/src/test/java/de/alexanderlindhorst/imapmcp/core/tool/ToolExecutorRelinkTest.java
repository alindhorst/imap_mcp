package de.alexanderlindhorst.imapmcp.core.tool;

import static org.assertj.core.api.Assertions.assertThat;

import de.alexanderlindhorst.imapmcp.core.crypto.KeyRing;
import de.alexanderlindhorst.imapmcp.core.crypto.LogIds;
import de.alexanderlindhorst.imapmcp.core.error.ErrorCode;
import de.alexanderlindhorst.imapmcp.core.imap.pool.AccountNeedsRelinkException;
import de.alexanderlindhorst.imapmcp.core.imap.pool.StoreUnavailableException;
import de.alexanderlindhorst.imapmcp.core.tenancy.AccountContext;
import de.alexanderlindhorst.imapmcp.core.tenancy.TenantId;
import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** An account flipped between resolve and borrow ends as relink_required with the link URL of the call. */
class ToolExecutorRelinkTest {

    final ToolExecutor executor = new ToolExecutor(Duration.ofSeconds(5),
            new LogIds(new KeyRing(1, Map.of(1, new byte[32]))));

    ToolDefinition def() {
        Map<String, Object> schema = Map.of("type", "object");
        return new ToolDefinition("list_folders", "L", "d", schema, schema, ToolHints.READ_ONLY_OPEN_WORLD);
    }

    @Test
    void theLinkUrlBoundByTheAccountResolutionIsAddedToTheRelinkError() {
        ToolCallContext ctx = new ToolCallContext(new AccountContext(new TenantId("t")), "r1");
        ToolOutcome out = executor.wrap(def(), (cx, a) -> {
            cx.bindLinkUrl("https://mail.example.com/accounts");
            throw new StoreUnavailableException(new AccountNeedsRelinkException());
        }).handle(ctx, Map.of());
        var e = ((ToolOutcome.Failure) out).error();
        assertThat(e.code()).isEqualTo(ErrorCode.AUTH_FAILED);
        assertThat(e.details()).containsEntry("reason", "relink_required")
                .containsEntry("link_url", "https://mail.example.com/accounts");
    }

    @Test
    void withoutABoundLinkUrlTheReasonStaysAndTheUrlIsOmitted() {
        ToolCallContext ctx = new ToolCallContext(new AccountContext(new TenantId("t")), "r2");
        ToolOutcome out = executor.wrap(def(), (cx, a) -> {
            throw new StoreUnavailableException(new AccountNeedsRelinkException());
        }).handle(ctx, Map.of());
        var e = ((ToolOutcome.Failure) out).error();
        assertThat(e.details()).containsEntry("reason", "relink_required").doesNotContainKey("link_url");
    }
}
