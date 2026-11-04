package de.alexanderlindhorst.imapmcp.it;

import static org.assertj.core.api.Assertions.assertThat;

import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.ServerParameters;
import io.modelcontextprotocol.client.transport.StdioClientTransport;
import io.modelcontextprotocol.json.McpJsonDefaults;
import io.modelcontextprotocol.spec.McpSchema;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/**
 * The server under smoke test: the shaded jar as a subprocess of the SDK's stdio client (as in the other end-to-end
 * ITs). Failure messages carry tool names and error codes only, never arguments or results, because the account is
 * real: no mail content or address may reach the build output.
 */
final class SmokeClient implements AutoCloseable {

    /** A tool result; {@code structured} is null only when the call produced none. */
    record Result(String tool, boolean isError, Map<String, Object> structured) {

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> list(String key) {
            return (List<Map<String, Object>>) structured.get(key);
        }

        @SuppressWarnings("unchecked")
        Map<String, Object> map(String key) {
            return (Map<String, Object>) structured.get(key);
        }

        String errorCode() {
            return map("error") == null ? null : (String) map("error").get("code");
        }

        List<String> itemStatuses() {
            return list("results").stream().map(i -> (String) i.get("status")).toList();
        }

        String newHandle(int i) {
            return (String) list("results").get(i).get("new_handle");
        }
    }

    private final Path dir;
    private final McpSyncClient client;
    private final String account;

    private SmokeClient(Path dir, McpSyncClient client, String account) {
        this.dir = dir;
        this.client = client;
        this.account = account;
    }

    static SmokeClient start(SmokeEnv.Settings settings, boolean writable) throws IOException {
        Path dir = Files.createTempDirectory("imap-mcp-smoke");
        ServerConfig config = SmokeConfig.write(dir, settings, writable);
        List<String> cmd = JarLauncher.builder(config, "stdio").command();
        ServerParameters params = ServerParameters.builder(cmd.get(0)).args(cmd.subList(1, cmd.size()))
                .env(config.env()).build();
        StdioClientTransport transport = new StdioClientTransport(params, McpJsonDefaults.getMapper());
        // The server's stderr is not forwarded: logs go to the temp log dir and may hold more than we want in a build log.
        transport.setStdErrorHandler(line -> { });
        McpSyncClient client = McpClient.sync(transport).requestTimeout(Duration.ofSeconds(120))
                .initializationTimeout(Duration.ofSeconds(60)).build();
        client.initialize();
        SmokeClient smoke = new SmokeClient(dir, client, null);
        String id = smoke.call("list_accounts", Map.of()).list("accounts").get(0).get("account").toString();
        return new SmokeClient(dir, client, id);
    }

    String account() {
        return account;
    }

    /** Calls a tool with the account argument filled in; {@code kv} are further key/value pairs. */
    Result tool(String tool, Object... kv) {
        Map<String, Object> args = new LinkedHashMap<>();
        if (account != null && !"list_accounts".equals(tool)) {
            args.put("account", account);
        }
        for (int i = 0; i < kv.length; i += 2) {
            args.put((String) kv[i], kv[i + 1]);
        }
        return call(tool, args);
    }

    /** As {@link #tool} and asserts the call itself succeeded (item level statuses are the caller's business). */
    Result ok(String tool, Object... kv) {
        Result r = tool(tool, kv);
        assertThat(r.isError()).as("%s failed with %s", tool, r.errorCode()).isFalse();
        return r;
    }

    @SuppressWarnings("unchecked")
    private Result call(String tool, Map<String, Object> args) {
        McpSchema.CallToolResult r = client.callTool(new McpSchema.CallToolRequest(tool, args));
        Map<String, Object> structured = r.structuredContent() instanceof Map<?, ?> m
                ? (Map<String, Object>) m : Map.of();
        return new Result(tool, Boolean.TRUE.equals(r.isError()), structured);
    }

    /** All handles in a folder, found by paging through {@code search_messages}. */
    List<Map<String, Object>> search(Object... kv) {
        List<Map<String, Object>> all = new ArrayList<>();
        String cursor = null;
        do {
            List<Object> args = new ArrayList<>(List.of(kv));
            if (cursor != null) {
                args.add("cursor");
                args.add(cursor);
            }
            Result r = ok("search_messages", args.toArray());
            all.addAll(r.list("messages"));
            cursor = (String) r.structured().get("next_cursor");
        } while (cursor != null);
        return all;
    }

    @Override
    public void close() {
        client.close();
        try (Stream<Path> files = Files.walk(dir)) {
            files.sorted(Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
        } catch (IOException ignored) {
            // temp dir, best effort
        }
    }
}
