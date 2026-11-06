package de.alexanderlindhorst.imapmcp.core.mcp;

import de.alexanderlindhorst.imapmcp.core.tool.ElicitationChannel;
import io.modelcontextprotocol.server.McpSyncServerExchange;
import io.modelcontextprotocol.spec.McpSchema;
import java.time.Duration;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * {@link ElicitationChannel} over the stateful SDK exchange of one tool call (SPEC 5.5). The sync exchange blocks
 * without a timeout, so the request runs on a helper virtual thread and the caller waits for it with the window:
 * at the window's end, or when the calling worker is interrupted (deadline, cancel), the helper is interrupted,
 * which makes Reactor dispose the request. An answer that arrives later is discarded by the SDK; the SDK keeps the
 * pending-response entry until then (small, harmless). Nothing of the message or the answer is logged.
 */
final class McpElicitationChannel implements ElicitationChannel {

    private final McpSyncServerExchange exchange;

    McpElicitationChannel(McpSyncServerExchange exchange) {
        this.exchange = Objects.requireNonNull(exchange, "exchange");
    }

    /** Form mode is declared by {@code form}, or by an empty {@code elicitation} object; URL mode alone is not enough. */
    @Override
    public boolean supportsFormMode() {
        McpSchema.ClientCapabilities caps = exchange.getClientCapabilities();
        McpSchema.ClientCapabilities.Elicitation e = caps == null ? null : caps.elicitation();
        return e != null && (e.form() != null || e.url() == null);
    }

    @Override
    public String sessionId() {
        String id = exchange.sessionId();
        return id == null ? STDIO_SESSION : id;
    }

    @Override
    public Answer ask(String message, Map<String, Object> requestedSchema, Duration window)
            throws InterruptedException {
        McpSchema.ElicitRequest request = McpSchema.ElicitFormRequest.builder(message, requestedSchema).build();
        CompletableFuture<McpSchema.ElicitResult> done = new CompletableFuture<>();
        Thread helper = Thread.ofVirtual().name("elicitation").unstarted(() -> {
            try {
                done.complete(exchange.createElicitation(request));
            } catch (Throwable t) {
                done.completeExceptionally(t);
            }
        });
        helper.start();
        try {
            McpSchema.ElicitResult result = done.get(window.toNanos(), TimeUnit.NANOSECONDS);
            return map(result);
        } catch (TimeoutException e) {
            helper.interrupt();
            return new Answer.TimedOut();
        } catch (InterruptedException e) {
            helper.interrupt();
            throw e;
        } catch (ExecutionException e) {
            return new Answer.Failed(); // class and message may carry client text: dropped
        }
    }

    private static Answer map(McpSchema.ElicitResult r) {
        if (r == null || r.action() == null) {
            return new Answer.Failed();
        }
        return switch (r.action()) {
            case ACCEPT -> new Answer.Accepted(r.content());
            case DECLINE -> new Answer.Declined();
            case CANCEL -> new Answer.Cancelled();
        };
    }
}
