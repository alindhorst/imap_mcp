package de.alexanderlindhorst.imapmcp.core.mcp;

import static org.assertj.core.api.Assertions.assertThat;

import de.alexanderlindhorst.imapmcp.core.error.ToolError;
import de.alexanderlindhorst.imapmcp.core.tenancy.AccountContext;
import de.alexanderlindhorst.imapmcp.core.tenancy.TenantId;
import de.alexanderlindhorst.imapmcp.core.tool.ToolDefinition;
import de.alexanderlindhorst.imapmcp.core.tool.ToolHints;
import de.alexanderlindhorst.imapmcp.core.tool.ToolOutcome;
import de.alexanderlindhorst.imapmcp.core.tool.ToolCallContext;
import io.modelcontextprotocol.common.McpTransportContext;
import io.modelcontextprotocol.json.McpJsonDefaults;
import io.modelcontextprotocol.json.McpJsonMapper;
import io.modelcontextprotocol.spec.McpSchema;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class McpToolAdapterTest {

    static final String DESC = "Count things in the account. Read-only; does not change flags or mark messages as read.";
    static final Map<String, Object> INPUT = Map.of("type", "object",
            "properties", Map.of("n", Map.of("type", "integer", "minimum", 1, "maximum", 10)), "additionalProperties", false);
    static final Map<String, Object> OUTPUT = Map.of("type", "object",
            "properties", Map.of("count", Map.of("type", "integer")), "required", List.of("count"));
    static final ToolDefinition SAMPLE =
            new ToolDefinition("count_things", "Count things", DESC, INPUT, OUTPUT, ToolHints.READ_ONLY_OPEN_WORLD);

    static final McpJsonMapper MAPPER = McpJsonDefaults.getMapper();
    static final McpTransportContext TCTX = McpTransportContext.EMPTY;

    final List<ToolCallContext> seen = new ArrayList<>();
    final AtomicInteger ids = new AtomicInteger();
    final McpToolAdapter adapter = new McpToolAdapter(ctx -> new AccountContext(new TenantId("tenant-from-transport")),
            MAPPER, () -> "req-" + ids.incrementAndGet());

    private ToolOutcome ok(ToolCallContext c, Map<String, Object> args) {
        seen.add(c);
        return new ToolOutcome.Success(Map.of("count", 3));
    }

    private static String textOf(McpSchema.CallToolResult r) {
        assertThat(r.content()).hasSize(1);
        return ((McpSchema.TextContent) r.content().get(0)).text();
    }

    @Test
    void toolCarriesAnnotationsAndSchemas() {
        McpSchema.Tool t = adapter.tool(SAMPLE);
        assertThat(t.name()).isEqualTo("count_things");
        assertThat(t.title()).isEqualTo("Count things");
        assertThat(t.description()).isEqualTo(DESC);
        McpSchema.ToolAnnotations a = t.annotations();
        assertThat(a.title()).isEqualTo("Count things");
        assertThat(a.readOnlyHint()).isTrue();
        assertThat(a.destructiveHint()).isFalse();
        assertThat(a.idempotentHint()).isTrue();
        assertThat(a.openWorldHint()).isTrue();
        assertThat(a.returnDirect()).isNull();
        assertThat(t.outputSchema()).isEqualTo(OUTPUT);
        assertThat(t.inputSchema()).containsEntry("type", "object");
    }

    @Test
    void successResultHasStructuredContentAndOneMatchingText() throws Exception {
        McpSchema.CallToolResult r = adapter.stateless(SAMPLE, this::ok).callHandler()
                .apply(TCTX, new McpSchema.CallToolRequest("count_things", Map.of()));
        assertThat(r.isError()).isNotEqualTo(Boolean.TRUE);
        assertThat(r.structuredContent()).isEqualTo(Map.of("count", 3));
        assertThat(MAPPER.readValue(textOf(r), Map.class)).isEqualTo(Map.of("count", 3));
        assertThat(textOf(r)).isEqualTo(MAPPER.writeValueAsString(r.structuredContent()));
    }

    @Test
    void failureResultIsErrorWithPayloadAndMatchingText() throws Exception {
        McpSchema.CallToolResult r = adapter.stateless(SAMPLE, (c, a) -> new ToolOutcome.Failure(ToolError.timeout(c.requestId())))
                .callHandler().apply(TCTX, new McpSchema.CallToolRequest("count_things", Map.of()));
        assertThat(r.isError()).isTrue();
        assertThat(ErrorResults.payload(r)).isEqualTo(ToolError.timeout("req-1").toMap());
        assertThat(textOf(r)).isEqualTo(MAPPER.writeValueAsString(ToolError.timeout("req-1").toMap()));
    }

    @Test
    void errorResultHasNoStructuredContentAndExactlyOneTextBlockWithErrorObject() throws Exception {
        McpSchema.CallToolResult r = adapter.stateless(SAMPLE, (c, a) -> new ToolOutcome.Failure(ToolError.timeout(c.requestId())))
                .callHandler().apply(TCTX, new McpSchema.CallToolRequest("count_things", Map.of()));
        assertThat(r.isError()).isTrue();
        assertThat(r.structuredContent()).isNull();
        assertThat(r.content()).hasSize(1);
        assertThat(r.content().get(0)).isInstanceOf(McpSchema.TextContent.class);
        assertThat(MAPPER.readValue(textOf(r), Map.class)).isEqualTo(ToolError.timeout("req-1").toMap());
    }

    @Test
    void handlerExceptionBecomesInternalErrorWithoutLeakingMessage() {
        McpSchema.CallToolResult r = adapter.stateless(SAMPLE, (c, a) -> {
            throw new IllegalStateException("secret subject line");
        }).callHandler().apply(TCTX, new McpSchema.CallToolRequest("count_things", Map.of()));
        assertThat(r.isError()).isTrue();
        assertThat(ErrorResults.payload(r)).isEqualTo(ToolError.internalError("req-1").toMap());
        assertThat(textOf(r)).doesNotContain("secret");
    }

    @Test
    void identityComesFromTransportNotFromArguments() {
        // even if a tool declared such parameters, they never reach the AccountContext
        Map<String, Object> input = Map.of("type", "object", "additionalProperties", false,
                "properties", Map.of("tenant", Map.of("type", "string"), "tenantId", Map.of("type", "string")));
        ToolDefinition def = new ToolDefinition("count_things", "Count things", DESC, input, OUTPUT, ToolHints.READ_ONLY_OPEN_WORLD);
        adapter.stateless(def, this::ok).callHandler().apply(TCTX,
                new McpSchema.CallToolRequest("count_things", Map.of("tenant", "evil", "tenantId", "evil")));
        assertThat(seen).hasSize(1);
        assertThat(seen.get(0).accountContext().tenantId()).isEqualTo(new TenantId("tenant-from-transport"));
        assertThat(seen.get(0).requestId()).isEqualTo("req-1");
    }

    @Test
    void failedIdentityResolutionIsInternalErrorAndHandlerNotCalled() {
        McpToolAdapter bad = new McpToolAdapter(ctx -> {
            throw new IllegalStateException("no principal");
        }, MAPPER, () -> "req-x");
        McpSchema.CallToolResult r = bad.stateless(SAMPLE, this::ok).callHandler()
                .apply(TCTX, new McpSchema.CallToolRequest("count_things", Map.of()));
        assertThat(r.isError()).isTrue();
        assertThat(seen).isEmpty();
    }

    @Test
    void everyToolRunsThroughExecutorOnVirtualThreadWithMdc() {
        java.util.concurrent.atomic.AtomicReference<Boolean> virtual = new java.util.concurrent.atomic.AtomicReference<>();
        java.util.concurrent.atomic.AtomicReference<String> req = new java.util.concurrent.atomic.AtomicReference<>();
        adapter.stateless(SAMPLE, (c, a) -> {
            virtual.set(Thread.currentThread().isVirtual());
            req.set(org.slf4j.MDC.get("req"));
            return new ToolOutcome.Success(Map.of("count", 1));
        }).callHandler().apply(TCTX, new McpSchema.CallToolRequest("count_things", Map.of()));
        assertThat(virtual.get()).isTrue();
        assertThat(req.get()).isEqualTo("req-1");
    }

    @Test
    void deadlineThroughAdapterYieldsTimeoutResult() {
        McpToolAdapter fast = new McpToolAdapter(ctx -> new AccountContext(new TenantId("t")), MAPPER, () -> "req-t",
                new de.alexanderlindhorst.imapmcp.core.tool.ToolExecutor(java.time.Duration.ofMillis(100),
                        new de.alexanderlindhorst.imapmcp.core.crypto.LogIds(new de.alexanderlindhorst.imapmcp.core.crypto.KeyRing(1,
                                Map.of(1, new byte[32])))));
        McpSchema.CallToolResult r = fast.stateless(SAMPLE, (c, a) -> {
            try {
                Thread.sleep(5000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return new ToolOutcome.Success(Map.of("count", 1));
        }).callHandler().apply(TCTX, new McpSchema.CallToolRequest("count_things", Map.of()));
        assertThat(r.isError()).isTrue();
        assertThat(ErrorResults.payload(r)).isEqualTo(ToolError.timeout("req-t").toMap());
    }

    @Test
    void statefulAndStatelessSpecsExposeIdenticalToolJson() throws Exception {
        String a = MAPPER.writeValueAsString(adapter.stateful(SAMPLE, this::ok).tool());
        String b = MAPPER.writeValueAsString(adapter.stateless(SAMPLE, this::ok).tool());
        assertThat(a).isEqualTo(b);
        assertThat(a).contains("\"readOnlyHint\":true").contains("\"outputSchema\"");
    }

    @Test
    void statefulHandlerUsesExchangeTransportContext() {
        InMemoryMcp.Stateful s = new InMemoryMcp.Stateful(adapter.stateful(SAMPLE, this::ok));
        McpSchema.CallToolResult r = s.call("count_things", Map.of());
        assertThat(r.structuredContent()).isEqualTo(Map.of("count", 3));
        assertThat(seen.get(0).accountContext().tenantId()).isEqualTo(new TenantId("tenant-from-transport"));
    }

    @Test
    void statefulHandlerGetsTheSessionsElicitationChannelAndStatelessGetsNone() {
        new InMemoryMcp.Stateful(adapter.stateful(SAMPLE, this::ok)).call("count_things", Map.of());
        assertThat(seen.get(0).elicitation()).isPresent().get().isInstanceOf(McpElicitationChannel.class);
        new InMemoryMcp.Stateless(adapter.stateless(SAMPLE, this::ok)).call("count_things", Map.of(), TCTX);
        assertThat(seen.get(1).elicitation()).isEmpty();
    }

    // --- through real SDK servers (AC3, AC4) ---

    @Test
    void errorResultPassesThroughRealServersUnchanged() {
        var spec = adapter.stateless(SAMPLE, (c, a) -> new ToolOutcome.Failure(ToolError.authFailed(c.requestId())));
        McpSchema.CallToolResult r = new InMemoryMcp.Stateless(spec).call("count_things", Map.of(), TCTX);
        assertThat(r.isError()).isTrue();
        assertThat(ErrorResults.payload(r)).isEqualTo(ToolError.authFailed("req-1").toMap());

        var spec2 = adapter.stateful(SAMPLE, (c, a) -> new ToolOutcome.Failure(ToolError.authFailed(c.requestId())));
        McpSchema.CallToolResult r2 = new InMemoryMcp.Stateful(spec2).call("count_things", Map.of());
        assertThat(r2.isError()).isTrue();
        assertThat(ErrorResults.payload(r2)).isEqualTo(ToolError.authFailed("req-2").toMap());
    }

    @Test
    void successPassesThroughRealServers() {
        McpSchema.CallToolResult r = new InMemoryMcp.Stateless(adapter.stateless(SAMPLE, this::ok))
                .call("count_things", Map.of(), TCTX);
        assertThat(r.isError()).isNotEqualTo(Boolean.TRUE);
        assertThat(r.structuredContent()).isEqualTo(Map.of("count", 3));
        McpSchema.CallToolResult r2 = new InMemoryMcp.Stateful(adapter.stateful(SAMPLE, this::ok)).call("count_things", Map.of());
        assertThat(r2.structuredContent()).isEqualTo(Map.of("count", 3));
    }

    @Test
    void outputViolatingSchemaBecomesErrorResultInRealServers() {
        ToolOutcome wrong = new ToolOutcome.Success(Map.of("count", "three"));
        McpSchema.CallToolResult r = new InMemoryMcp.Stateless(adapter.stateless(SAMPLE, (c, a) -> wrong))
                .call("count_things", Map.of(), TCTX);
        assertThat(r.isError()).isTrue();
        assertThat(r.structuredContent()).isNull();
        assertThat(textOf(r)).containsIgnoringCase("valid");
        McpSchema.CallToolResult r2 = new InMemoryMcp.Stateful(adapter.stateful(SAMPLE, (c, a) -> wrong)).call("count_things", Map.of());
        assertThat(r2.isError()).isTrue();
    }

    // --- #12: validation runs before every handler ---

    @Test
    void invalidInputIsErrorResultAndHandlerNotInvoked() {
        McpSchema.CallToolResult r = adapter.stateless(SAMPLE, this::ok).callHandler()
                .apply(TCTX, new McpSchema.CallToolRequest("count_things", Map.of("n", 11)));
        assertThat(r.isError()).isTrue();
        assertThat(ErrorResults.payload(r)).isEqualTo(ToolError.invalidArgument("n", "out_of_range", 10, "req-1").toMap());
        assertThat(seen).isEmpty();
    }

    @Test
    void invalidInputThroughRealServersIsToolErrorNotJsonRpcError() {
        McpSchema.CallToolResult r = new InMemoryMcp.Stateless(adapter.stateless(SAMPLE, this::ok))
                .call("count_things", Map.of("n", "5"), TCTX);
        assertThat(r.isError()).isTrue();
        assertThat(ErrorResults.payload(r)).isEqualTo(ToolError.invalidArgument("n", "type", null, "req-1").toMap());
        McpSchema.CallToolResult r2 = new InMemoryMcp.Stateful(adapter.stateful(SAMPLE, this::ok))
                .call("count_things", Map.of("n", 0));
        assertThat(r2.isError()).isTrue();
        assertThat(seen).isEmpty();
    }
}
