package de.alexanderlindhorst.imapmcp.core.mcp;

import de.alexanderlindhorst.imapmcp.core.error.ToolError;
import de.alexanderlindhorst.imapmcp.core.tenancy.AccountContext;
import de.alexanderlindhorst.imapmcp.core.tool.ToolCallContext;
import de.alexanderlindhorst.imapmcp.core.tool.ToolDefinition;
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
 * transport from the validated principal), never from tool arguments. Both result shapes carry
 * {@code structuredContent} plus exactly one text block with the same JSON, as MCP clients that ignore
 * structured output still need the text. The SDK validates a success result's structuredContent against
 * the tool's outputSchema and turns a mismatch into an error result; error results are not validated.
 *
 * <p>Input validation is ours ({@link ValidatingToolHandler}, wrapped around every handler here) so that
 * errors are INVALID_ARGUMENT tool results with exact field/reason. Servers built with these specs MUST call
 * {@code validateToolInputs(false)}: the SDK default validates first and answers with its own, differently
 * shaped error without structuredContent.
 */
public final class McpToolAdapter {

    private static final Logger LOG = LoggerFactory.getLogger(McpToolAdapter.class);

    private final Function<McpTransportContext, AccountContext> accountResolver;
    private final McpJsonMapper mapper;
    private final Supplier<String> requestIds;

    public McpToolAdapter(Function<McpTransportContext, AccountContext> accountResolver) {
        this(accountResolver, McpJsonDefaults.getMapper(), () -> UUID.randomUUID().toString());
    }

    public McpToolAdapter(Function<McpTransportContext, AccountContext> accountResolver, McpJsonMapper mapper,
            Supplier<String> requestIds) {
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
                .callHandler((exchange, request) -> call(validating(def, handler), exchange.transportContext(), request)).build();
    }

    /** Stateless (HTTP) flavour. */
    public McpStatelessServerFeatures.SyncToolSpecification stateless(ToolDefinition def, ToolHandler handler) {
        return McpStatelessServerFeatures.SyncToolSpecification.builder().tool(tool(def))
                .callHandler((transportContext, request) -> call(validating(def, handler), transportContext, request)).build();
    }

    /** Validation is applied here so no registered tool can run on unvalidated input (outermost wrapper). */
    private static ToolHandler validating(ToolDefinition def, ToolHandler handler) {
        return new ValidatingToolHandler(def, handler);
    }

    private McpSchema.CallToolResult call(ToolHandler handler, McpTransportContext transportContext,
            McpSchema.CallToolRequest request) {
        String requestId = requestIds.get();
        ToolOutcome outcome;
        try {
            ToolCallContext ctx = new ToolCallContext(accountResolver.apply(transportContext), requestId);
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
        return McpSchema.CallToolResult.builder().structuredContent(structured).addTextContent(json).isError(isError).build();
    }
}
