package de.alexanderlindhorst.imapmcp.core.mcp;

import static org.assertj.core.api.Assertions.assertThat;

import de.alexanderlindhorst.imapmcp.core.tenancy.AccountContext;
import de.alexanderlindhorst.imapmcp.core.tool.DescriptionRules;
import de.alexanderlindhorst.imapmcp.core.tools.SearchEnv;
import de.alexanderlindhorst.imapmcp.core.tools.SearchMessagesTool;
import io.modelcontextprotocol.common.McpTransportContext;
import io.modelcontextprotocol.json.McpJsonDefaults;
import io.modelcontextprotocol.spec.McpSchema;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** search_messages through the real SDK (tools/list entry, output-schema validation); SDK types stay in core.mcp. */
class SearchMessagesSdkTest {

    SearchEnv env;
    InMemoryMcp.Stateless mcp;

    @BeforeEach
    void setUp() {
        env = new SearchEnv();
        mcp = new InMemoryMcp.Stateless(new McpToolAdapter(c -> new AccountContext(SearchEnv.T1))
                .stateless(SearchMessagesTool.DEFINITION, env.tool));
    }

    @AfterEach
    void tearDown() {
        env.close();
    }

    @Test
    void toolsListEntryHasAnnotationsAndSchemas() {
        McpSchema.ListToolsResult listed = McpJsonDefaults.getMapper().convertValue(
                mcp.request(McpSchema.METHOD_TOOLS_LIST, Map.of(), McpTransportContext.EMPTY).result(),
                McpSchema.ListToolsResult.class);
        McpSchema.Tool t = listed.tools().get(0);
        assertThat(DescriptionRules.violations(SearchMessagesTool.DEFINITION)).isEmpty();
        assertThat(t.name()).isEqualTo("search_messages");
        assertThat(t.title()).isEqualTo("Search messages");
        assertThat(t.annotations().readOnlyHint()).isTrue();
        assertThat(t.annotations().destructiveHint()).isFalse();
        assertThat(t.annotations().idempotentHint()).isTrue();
        assertThat(t.annotations().openWorldHint()).isTrue();
        assertThat(t.outputSchema()).containsKey("properties");
        assertThat(t.inputSchema()).containsEntry("additionalProperties", false);
    }

    @Test
    void firstAndCursorPagePassTheSdksOutputSchemaValidationIncludingNulls() throws Exception {
        env.deliver(5);
        Map<String, Object> args = new LinkedHashMap<>();
        args.put("account", env.handleT1);
        args.put("page_size", 2);
        McpSchema.CallToolResult first = mcp.call("search_messages", args, McpTransportContext.EMPTY);
        assertThat(first.isError()).isNotEqualTo(Boolean.TRUE);
        Map<?, ?> out = (Map<?, ?>) first.structuredContent();
        assertThat(out.get("as_of")).isNotNull();
        args.put("cursor", out.get("next_cursor"));
        McpSchema.CallToolResult second = mcp.call("search_messages", args, McpTransportContext.EMPTY);
        assertThat(second.isError()).isNotEqualTo(Boolean.TRUE);
        assertThat(((Map<?, ?>) second.structuredContent()).get("as_of")).isNull();
    }
}
