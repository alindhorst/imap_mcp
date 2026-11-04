package de.alexanderlindhorst.imapmcp.it;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import de.alexanderlindhorst.imapmcp.app.AppComponents;
import de.alexanderlindhorst.imapmcp.app.AppComponentsAccess;
import de.alexanderlindhorst.imapmcp.app.config.AppConfig;
import de.alexanderlindhorst.imapmcp.app.config.AppPaths;
import de.alexanderlindhorst.imapmcp.app.config.ConfigLoader;
import de.alexanderlindhorst.imapmcp.core.account.PoolKey;
import de.alexanderlindhorst.imapmcp.core.account.ResolvedAccount;
import de.alexanderlindhorst.imapmcp.core.imap.pool.StorePool;
import io.modelcontextprotocol.common.McpTransportContext;
import io.modelcontextprotocol.server.McpServerFeatures;
import io.modelcontextprotocol.server.McpSyncServerExchange;
import io.modelcontextprotocol.spec.McpSchema;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The real tools in-process ({@link AppComponents}, the call handlers of the stdio transport) against one IMAPS
 * server, one tenant, one writable account {@code work}, {@code imap.trace: true} and {@code tools.deadline: 30s}
 * (the minimum for a writable account). The shared base of the #207 ITs; WriteInterruptionIT keeps its own copy.
 */
final class WriteHarness implements AutoCloseable {

    /** A tool result with the accessors the ITs need. */
    record Result(McpSchema.CallToolResult raw, Duration took) {

        @SuppressWarnings("unchecked")
        Map<String, Object> structured() {
            return (Map<String, Object>) raw.structuredContent();
        }

        boolean isError() {
            return Boolean.TRUE.equals(raw.isError());
        }

        @SuppressWarnings("unchecked")
        Map<String, Object> error() {
            return (Map<String, Object>) structured().get("error");
        }

        String code() {
            return (String) error().get("code");
        }

        @SuppressWarnings("unchecked")
        Map<String, Object> details() {
            return (Map<String, Object>) error().get("details");
        }

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> items() {
            return (List<Map<String, Object>>) structured().get("results");
        }

        @SuppressWarnings("unchecked")
        Map<String, Object> summary() {
            return (Map<String, Object>) structured().get("summary");
        }

        @SuppressWarnings("unchecked")
        List<String> notes() {
            return (List<String>) structured().get("notes");
        }

        List<String> statuses() {
            return items().stream().map(i -> (String) i.get("status")).toList();
        }

        String newHandle(int i) {
            return (String) items().get(i).get("new_handle");
        }
    }

    final AppComponents components;
    final StorePool pool;
    final PoolKey key;
    final String account;
    private final Map<String, McpServerFeatures.SyncToolSpecification> specs = new HashMap<>();
    private final McpSyncServerExchange exchange = mock(McpSyncServerExchange.class);
    private final Map<String, Object> capabilities;

    WriteHarness(Path dir, String host, int port, String user, String password) throws Exception {
        Path config = dir.resolve(user + ".yaml");
        Files.writeString(config, """
                accounts:
                  - id: work
                    host: %s
                    port: %d
                    username: %s
                    password: "env:IT_PW"
                imap:
                  readTimeout: 60s
                  trace: true
                tools:
                  deadline: 30s
                """.formatted(host, port, user));
        AppConfig cfg = ConfigLoader.load(config, Map.of());
        AppPaths paths = new AppPaths(config, dir.resolve("state-" + user), dir.resolve("logs-" + user));
        components = AppComponentsAccess.create(cfg, paths, Map.of("IT_PW", password), p -> {
            p.put("mail.imaps.ssl.trust", "*");
            p.put("mail.imaps.ssl.checkserveridentity", "false");
            return p;
        });
        pool = AppComponentsAccess.pool(components);
        key = PoolKey.of(components.accountContext(),
                new ResolvedAccount(AppComponents.TENANT, cfg.accounts().get(0).toMailAccount(), "acc_unused"));
        components.statefulToolSpecs().forEach(s -> specs.put(s.tool().name(), s));
        when(exchange.transportContext()).thenReturn(McpTransportContext.EMPTY);
        Map<String, Object> entry = accountEntry();
        account = (String) entry.get("account");
        @SuppressWarnings("unchecked")
        Map<String, Object> caps = (Map<String, Object>) entry.get("capabilities");
        capabilities = caps;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> accountEntry() {
        Map<String, Object> out = call("list_accounts", Map.of()).structured();
        return ((List<Map<String, Object>>) out.get("accounts")).get(0);
    }

    Map<String, Object> capabilities() {
        return capabilities;
    }

    boolean hasCapability(String name) {
        return Boolean.TRUE.equals(capabilities.get(name));
    }

    Result call(String tool, Map<String, Object> args) {
        long t0 = System.nanoTime();
        McpSchema.CallToolResult r = specs.get(tool).callHandler().apply(exchange,
                new McpSchema.CallToolRequest(tool, args));
        return new Result(r, Duration.ofNanos(System.nanoTime() - t0));
    }

    /** Same call with the account handle filled in. */
    Result callOn(String tool, Object... kv) {
        Map<String, Object> args = new java.util.LinkedHashMap<>();
        args.put("account", account);
        for (int i = 0; i < kv.length; i += 2) {
            args.put((String) kv[i], kv[i + 1]);
        }
        return call(tool, args);
    }

    /** Handles of the folder's messages (first page), newest first, through the real search tool. */
    @SuppressWarnings("unchecked")
    List<String> handles(String folder) {
        Map<String, Object> out = callOn("search_messages", "folder", folder).structured();
        List<String> handles = new ArrayList<>();
        for (Map<String, Object> m : (List<Map<String, Object>>) out.get("messages")) {
            handles.add((String) m.get("handle"));
        }
        return handles;
    }

    @Override
    public void close() {
        components.close();
    }
}
