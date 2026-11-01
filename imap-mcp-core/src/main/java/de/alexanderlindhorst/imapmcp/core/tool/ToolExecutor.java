package de.alexanderlindhorst.imapmcp.core.tool;

import de.alexanderlindhorst.imapmcp.core.crypto.LogIds;
import de.alexanderlindhorst.imapmcp.core.error.ImapErrorMapper;
import de.alexanderlindhorst.imapmcp.core.error.ToolError;
import java.time.Duration;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

/**
 * Runs every tool call on its own virtual thread with an overall deadline (SPEC 5.5). The calling SDK thread
 * waits for the result; on the deadline the context is cancelled (hooks run once, e.g. the lease discards its
 * Store), the worker is interrupted and TIMEOUT is returned without waiting for the worker. MDC (req, tool,
 * tenant hashed, acct hashed once bound) lives on the worker only and is cleared there.
 *
 * <p>The request id is created by the adapter and carried in the {@link ToolCallContext}.
 */
public final class ToolExecutor {

    public static final Duration DEFAULT_DEADLINE = Duration.ofSeconds(90);

    private static final Logger LOG = LoggerFactory.getLogger(ToolExecutor.class);

    private final Duration deadline;
    private final LogIds logIds;

    public ToolExecutor(Duration deadline, LogIds logIds) {
        this.deadline = Objects.requireNonNull(deadline, "deadline");
        this.logIds = Objects.requireNonNull(logIds, "logIds");
    }

    public ToolHandler wrap(String toolName, ToolHandler delegate) {
        Objects.requireNonNull(toolName, "toolName");
        Objects.requireNonNull(delegate, "delegate");
        return (ctx, args) -> execute(toolName, delegate, ctx, args);
    }

    private ToolOutcome execute(String toolName, ToolHandler delegate, ToolCallContext ctx, Map<String, Object> args) {
        CompletableFuture<ToolOutcome> future = new CompletableFuture<>();
        Thread worker = Thread.ofVirtual().name("tool-" + toolName).unstarted(() -> {
            try {
                MDC.put("req", ctx.requestId());
                MDC.put("tool", toolName);
                MDC.put("tenant", logIds.tenant(ctx.accountContext().tenantId().value()));
                ctx.attach(logIds);
                future.complete(delegate.handle(ctx, args));
            } catch (Throwable t) {
                future.completeExceptionally(t);
            } finally {
                MDC.clear();
            }
        });
        ctx.onCancel(worker::interrupt);
        worker.start();
        try {
            return future.get(deadline.toNanos(), TimeUnit.NANOSECONDS);
        } catch (TimeoutException e) {
            LOG.warn("tool {} exceeded deadline, request_id={}", toolName, ctx.requestId());
            ctx.cancel();
            return new ToolOutcome.Failure(ToolError.timeout(ctx.requestId()));
        } catch (InterruptedException e) {
            ctx.cancel();
            Thread.currentThread().interrupt();
            return new ToolOutcome.Failure(ToolError.timeout(ctx.requestId()));
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            // Class only: exception messages may carry mail content or credentials.
            LOG.error("tool {} failed, request_id={}, cause={}", toolName, ctx.requestId(), cause.getClass().getName());
            return new ToolOutcome.Failure(ImapErrorMapper.map(cause, ctx.requestId()).error());
        }
    }
}
