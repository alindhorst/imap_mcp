package de.alexanderlindhorst.imapmcp.transport.stdio;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import de.alexanderlindhorst.imapmcp.core.mcp.McpToolAdapter;
import de.alexanderlindhorst.imapmcp.core.tenancy.AccountContext;
import de.alexanderlindhorst.imapmcp.core.tenancy.TenantId;
import de.alexanderlindhorst.imapmcp.core.tool.ToolDefinition;
import de.alexanderlindhorst.imapmcp.core.tool.ToolHints;
import de.alexanderlindhorst.imapmcp.core.tool.ToolOutcome;
import io.modelcontextprotocol.server.McpServerFeatures;
import io.modelcontextprotocol.spec.McpSchema;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class StdioServerTest {

    private static final String DESC = "Count things in the account. Read-only; does not change flags or mark messages as read.";
    private static final Map<String, Object> INPUT = Map.of("type", "object",
            "properties", Map.of("n", Map.of("type", "integer", "minimum", 1, "maximum", 10)),
            "additionalProperties", false);
    private static final Map<String, Object> OUTPUT = Map.of("type", "object",
            "properties", Map.of("count", Map.of("type", "integer")), "required", List.of("count"));
    private static final ToolDefinition COUNT =
            new ToolDefinition("count_things", "Count things", DESC, INPUT, OUTPUT, ToolHints.READ_ONLY_OPEN_WORLD);
    private static final ToolDefinition SLOW =
            new ToolDefinition("slow_things", "Slow things", DESC, Map.of("type", "object", "additionalProperties", false),
                    OUTPUT, ToolHints.READ_ONLY_OPEN_WORLD);

    private final McpToolAdapter adapter = new McpToolAdapter(ctx -> new AccountContext(new TenantId("t")));
    private final StdioClientHarness client = new StdioClientHarness();
    private StdioServer server;

    private List<McpServerFeatures.SyncToolSpecification> tools() {
        return List.of(
                adapter.stateful(COUNT, (c, a) -> new ToolOutcome.Success(Map.of("count", 3))),
                adapter.stateful(SLOW, (c, a) -> {
                    try {
                        Thread.sleep(500);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                    return new ToolOutcome.Success(Map.of("count", 1));
                }));
    }

    private StdioServer startServer() {
        server = StdioServer.start(client.streams(), StdioClientHarness.MAPPER, tools(),
                new McpSchema.Implementation("imap-mcp", "0.1.0"));
        return server;
    }

    @AfterEach
    void stop() throws Exception {
        client.closeInput();
        if (server != null) {
            server.awaitTermination();
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> map(Object o) {
        return (Map<String, Object>) o;
    }

    @Test
    void initializeListAndCallWork() throws Exception {
        startServer();
        int init = client.request("initialize", Map.of("protocolVersion", io.modelcontextprotocol.spec.ProtocolVersions.MCP_2025_11_25,
                "capabilities", Map.of(), "clientInfo", Map.of("name", "test", "version", "1")));
        Map<String, Object> initResp = client.awaitResponse(init);
        assertThat(map(map(initResp.get("result")).get("serverInfo"))).containsEntry("name", "imap-mcp");
        client.notification("notifications/initialized");

        Map<String, Object> list = client.awaitResponse(client.request("tools/list", Map.of()));
        List<Object> listed = (List<Object>) map(list.get("result")).get("tools");
        Map<String, Object> count = listed.stream().map(StdioServerTest::map)
                .filter(t -> t.get("name").equals("count_things")).findFirst().orElseThrow();
        assertThat(map(count.get("annotations"))).containsEntry("readOnlyHint", true);

        Map<String, Object> call = client.awaitResponse(
                client.request("tools/call", Map.of("name", "count_things", "arguments", Map.of())));
        assertThat(map(map(call.get("result")).get("structuredContent"))).containsEntry("count", 3);
    }

    @Test
    void everyOutputLineIsJsonRpc() throws Exception {
        startServer();
        client.initialize();
        client.awaitResponse(client.request("tools/call", Map.of("name", "count_things", "arguments", Map.of())));
        client.closeInput();
        server.awaitTermination();
        String raw = client.rawOutput();
        assertThat(raw).isNotEmpty().endsWith("\n");
        for (String line : raw.split("\n", -1)) {
            if (line.isEmpty()) {
                continue;
            }
            assertThat(StdioClientHarness.MAPPER.readValue(line, Map.class)).containsEntry("jsonrpc", "2.0");
        }
    }

    @Test
    void inputEofReleasesAwaitTerminationAndLeavesNoNonDaemonThreads() throws Exception {
        Set<Thread> before = Thread.getAllStackTraces().keySet();
        startServer();
        client.initialize();
        client.awaitResponse(client.request("tools/list", Map.of()));
        client.closeInput();
        long start = System.nanoTime();
        server.awaitTermination();
        assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(Duration.ofSeconds(5));
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
        List<Thread> leftover;
        do {
            leftover = Thread.getAllStackTraces().keySet().stream()
                    .filter(t -> !before.contains(t) && t.isAlive() && !t.isDaemon()).toList();
            if (!leftover.isEmpty()) {
                Thread.sleep(50);
            }
        } while (!leftover.isEmpty() && System.nanoTime() < deadline);
        assertThat(leftover).as("non-daemon threads left behind").isEmpty();
        server = null;
    }

    @Test
    void slowCallsOverlap() throws Exception {
        startServer();
        client.initialize();
        long start = System.nanoTime();
        int a = client.request("tools/call", Map.of("name", "slow_things", "arguments", Map.of()));
        int b = client.request("tools/call", Map.of("name", "slow_things", "arguments", Map.of()));
        client.awaitResponse(a);
        client.awaitResponse(b);
        assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(Duration.ofMillis(900));
    }

    @Test
    void unknownPropertyIsOurInvalidArgumentNotSdkValidation() throws Exception {
        startServer();
        client.initialize();
        Map<String, Object> resp = client.awaitResponse(client.request("tools/call",
                Map.of("name", "count_things", "arguments", Map.of("bogus", 1))));
        Map<String, Object> result = map(resp.get("result"));
        Map<String, Object> sc = ErrorResults.error(result);
        assertThat(sc).containsEntry("code", "INVALID_ARGUMENT");
        assertThat(map(sc.get("details"))).containsEntry("reason", "unknown_property");
    }

    @Test
    void startWithoutVirtualThreadPropertyFailsWithActionableMessage() {
        String key = "reactor.schedulers.defaultBoundedElasticOnVirtualThreads";
        String old = System.getProperty(key);
        System.clearProperty(key);
        try {
            assertThatThrownBy(() -> StdioServer.start(client.streams(), StdioClientHarness.MAPPER, tools(),
                    new McpSchema.Implementation("imap-mcp", "0.1.0")))
                    .isInstanceOf(IllegalStateException.class).hasMessageContaining(key);
        } finally {
            System.setProperty(key, old);
        }
    }
}
