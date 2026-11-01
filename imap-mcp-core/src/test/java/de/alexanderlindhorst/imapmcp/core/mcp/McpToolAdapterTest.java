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
    static final Map<String, Object> INPUT = Map.of("type", "object", "properties", Map.of("n", Map.of("type", "integer")));
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
        assertThat(r.structuredContent()).isEqualTo(ToolError.timeout("req-1").toMap());
        assertThat(textOf(r)).isEqualTo(MAPPER.writeValueAsString(ToolError.timeout("req-1").toMap()));
    }

    @Test
    void handlerExceptionBecomesInternalErrorWithoutLeakingMessage() {
        McpSchema.CallToolResult r = adapter.stateless(SAMPLE, (c, a) -> {
            throw new IllegalStateException("secret subject line");
        }).callHandler().apply(TCTX, new McpSchema.CallToolRequest("count_things", Map.of()));
        assertThat(r.isError()).isTrue();
        assertThat(r.structuredContent()).isEqualTo(ToolError.internalError("req-1").toMap());
        assertThat(textOf(r)).doesNotContain("secret");
    }

    @Test
    void identityComesFromTransportNotFromArguments() {
        adapter.stateless(SAMPLE, this::ok).callHandler().apply(TCTX,
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

    // --- through real SDK servers (AC3, AC4) ---

    @Test
    void errorResultPassesThroughRealServersUnchanged() {
        var spec = adapter.stateless(SAMPLE, (c, a) -> new ToolOutcome.Failure(ToolError.authFailed(c.requestId())));
        McpSchema.CallToolResult r = new InMemoryMcp.Stateless(spec).call("count_things", Map.of(), TCTX);
        assertThat(r.isError()).isTrue();
        assertThat(r.structuredContent()).isEqualTo(ToolError.authFailed("req-1").toMap());

        var spec2 = adapter.stateful(SAMPLE, (c, a) -> new ToolOutcome.Failure(ToolError.authFailed(c.requestId())));
        McpSchema.CallToolResult r2 = new InMemoryMcp.Stateful(spec2).call("count_things", Map.of());
        assertThat(r2.isError()).isTrue();
        assertThat(r2.structuredContent()).isEqualTo(ToolError.authFailed("req-2").toMap());
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
        McpSchema.CallToolResult r2 = new InMemoryMcp.Stateful(adapter.stateful(SAMPLE, (c, a) -> wrong)).call("count_things", Map.of());
        assertThat(r2.isError()).isTrue();
    }
}
