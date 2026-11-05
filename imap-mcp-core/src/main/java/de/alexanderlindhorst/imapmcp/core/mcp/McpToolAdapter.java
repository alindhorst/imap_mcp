package de.alexanderlindhorst.imapmcp.core.mcp;

import de.alexanderlindhorst.imapmcp.core.crypto.KeyRing;
import de.alexanderlindhorst.imapmcp.core.crypto.LogIds;
import de.alexanderlindhorst.imapmcp.core.error.ToolError;
import de.alexanderlindhorst.imapmcp.core.tenancy.AccountContext;
import de.alexanderlindhorst.imapmcp.core.tool.ElicitationChannel;
import de.alexanderlindhorst.imapmcp.core.tool.ToolCallContext;
import de.alexanderlindhorst.imapmcp.core.tool.ToolDefinition;
import de.alexanderlindhorst.imapmcp.core.tool.ToolExecutor;
import de.alexanderlindhorst.imapmcp.core.tool.ToolHandler;
import de.alexanderlindhorst.imapmcp.core.tool.ToolOutcome;
import de.alexanderlindhorst.imapmcp.core.tool.ValidatingToolHandler;
import io.modelcontextprotocol.common.McpTransportContext;
import io.modelcontextprotocol.json.McpJsonDefaults;
import io.modelcontextprotocol.json.McpJsonMapper;
import io.modelcontextprotocol.server.McpServerFeatures;
import io.modelcontextprotocol.server.McpStatelessServerFeatures;
import io.modelcontextprotocol.spec.McpSchema;
import java.io.IOException;
import java.security.SecureRandom;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Function;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Turns one {@link ToolDefinition} + {@link ToolHandler} into SDK tool specifications for both server
 * flavours (stateful stdio, stateless HTTP). The only place that knows the MCP SDK (SPEC 5.1).
 *
 * <p>The {@link AccountContext} is resolved from the SDK's {@link McpTransportContext} (filled by the
 * transport from the validated principal), never from tool arguments. A success result carries
 * {@code structuredContent} plus exactly one text block with the same JSON, as MCP clients that ignore
 * structured output still need the text. The SDK validates a success result's structuredContent against
 * the tool's outputSchema and turns a mismatch into an error result.
 *
 * <p>Error results ({@code isError} true) carry the {@code {"error":{...}}} object only in that one text
 * block and no {@code structuredContent}: the outputSchema describes the success shape, and MCP clients that
 * validate structuredContent of error results would otherwise reject every tool error (SPEC 4).
 *
 * <p>Every handler is wrapped in the {@link ToolExecutor} and, inside it, the input validation ({@link ValidatingToolHandler}) so that
 * errors are INVALID_ARGUMENT tool results with exact field/reason. Servers built with these specs MUST call
 * {@code validateToolInputs(false)}: the SDK default validates first and answers with its own, differently
 * shaped error without structuredContent.
 */
public final class McpToolAdapter {

    private static final Logger LOG = LoggerFactory.getLogger(McpToolAdapter.class);

    private final Function<McpTransportContext, AccountContext> accountResolver;
    private final McpJsonMapper mapper;
    private final Supplier<String> requestIds;
    private final ToolExecutor executor;

    public McpToolAdapter(Function<McpTransportContext, AccountContext> accountResolver) {
        this(accountResolver, McpJsonDefaults.getMapper(), () -> UUID.randomUUID().toString());
    }

    /** Default executor (90 s deadline) with a throwaway log-id key; production wiring passes its own. */
    public McpToolAdapter(Function<McpTransportContext, AccountContext> accountResolver, McpJsonMapper mapper,
            Supplier<String> requestIds) {
        this(accountResolver, mapper, requestIds, new ToolExecutor(ToolExecutor.DEFAULT_DEADLINE, randomLogIds()));
    }

    public McpToolAdapter(Function<McpTransportContext, AccountContext> accountResolver, McpJsonMapper mapper,
            Supplier<String> requestIds, ToolExecutor executor) {
        this.executor = Objects.requireNonNull(executor, "executor");
        this.accountResolver = Objects.requireNonNull(accountResolver, "accountResolver");
        this.mapper = Objects.requireNonNull(mapper, "mapper");
        this.requestIds = Objects.requireNonNull(requestIds, "requestIds");
    }

    public McpSchema.Tool tool(ToolDefinition def) {
        McpSchema.ToolAnnotations annotations = new McpSchema.ToolAnnotations(def.title(), def.hints().readOnly(),
                def.hints().destructive(), def.hints().idempotent(), def.hints().openWorld(), null);
        return McpSchema.Tool.builder().name(def.name()).title(def.title()).description(def.description())
                .inputSchema(def.inputSchema()).outputSchema(def.outputSchema()).annotations(annotations).build();
    }

    /** Stateful (stdio) flavour; the account context comes from the exchange's transport context. */
    public McpServerFeatures.SyncToolSpecification stateful(ToolDefinition def, ToolHandler handler) {
        return McpServerFeatures.SyncToolSpecification.builder().tool(tool(def))
                .callHandler((exchange, request) -> call(validating(def, handler), exchange.transportContext(), request,
                        new McpElicitationChannel(exchange))).build();
    }

    /** Stateless (HTTP) flavour. */
    public McpStatelessServerFeatures.SyncToolSpecification stateless(ToolDefinition def, ToolHandler handler) {
        return McpStatelessServerFeatures.SyncToolSpecification.builder().tool(tool(def))
                .callHandler((transportContext, request) -> call(validating(def, handler), transportContext, request, null))
                .build();
    }

    private static LogIds randomLogIds() {
        byte[] key = new byte[32];
        new SecureRandom().nextBytes(key);
        return new LogIds(new KeyRing(1, Map.of(1, key)));
    }

    /**
     * Executor (virtual thread, deadline, MDC) outermost, validation inside it, so no registered tool can run
     * on unvalidated input or outside the deadline.
     */
    private ToolHandler validating(ToolDefinition def, ToolHandler handler) {
        return executor.wrap(def, new ValidatingToolHandler(def, handler));
    }

    /** @param elicitation the stateful session's way to ask the user; null for the stateless server (SPEC 5.5) */
    private McpSchema.CallToolResult call(ToolHandler handler, McpTransportContext transportContext,
            McpSchema.CallToolRequest request, ElicitationChannel elicitation) {
        String requestId = requestIds.get();
        ToolOutcome outcome;
        try {
            ToolCallContext ctx = new ToolCallContext(accountResolver.apply(transportContext), requestId);
            if (elicitation != null) {
                ctx.bindElicitation(elicitation);
            }
            Map<String, Object> args = request.arguments() == null ? Map.of() : request.arguments();
            outcome = handler.handle(ctx, args);
        } catch (RuntimeException e) {
            // Class only: exception messages may carry mail content or credentials.
            LOG.error("tool {} failed, request_id={}, cause={}", request.name(), requestId, e.getClass().getName());
            outcome = new ToolOutcome.Failure(ToolError.internalError(requestId));
        }
        return switch (outcome) {
            case ToolOutcome.Success s -> result(s.structured(), false, requestId);
            case ToolOutcome.Failure f -> result(f.error().toMap(), true, requestId);
        };
    }

    private McpSchema.CallToolResult result(Object structured, boolean isError, String requestId) {
        String json;
        try {
            json = mapper.writeValueAsString(structured);
        } catch (IOException | RuntimeException e) {
            LOG.error("result serialisation failed, request_id={}, cause={}", requestId, e.getClass().getName());
            return result(ToolError.internalError(requestId).toMap(), true, requestId);
        }
        McpSchema.CallToolResult.Builder b = McpSchema.CallToolResult.builder().addTextContent(json).isError(isError);
        return isError ? b.build() : b.structuredContent(structured).build();
    }
}
