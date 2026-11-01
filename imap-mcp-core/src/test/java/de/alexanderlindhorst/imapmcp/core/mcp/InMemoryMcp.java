package de.alexanderlindhorst.imapmcp.core.mcp;

import io.modelcontextprotocol.common.McpTransportContext;
import io.modelcontextprotocol.json.McpJsonDefaults;
import io.modelcontextprotocol.json.McpJsonMapper;
import io.modelcontextprotocol.json.TypeRef;
import io.modelcontextprotocol.server.McpServer;
import io.modelcontextprotocol.server.McpServerFeatures;
import io.modelcontextprotocol.server.McpStatelessServerFeatures;
import io.modelcontextprotocol.server.McpStatelessServerHandler;
import io.modelcontextprotocol.server.McpSyncServer;
import io.modelcontextprotocol.server.McpStatelessSyncServer;
import io.modelcontextprotocol.spec.McpSchema;
import io.modelcontextprotocol.spec.McpServerSession;
import io.modelcontextprotocol.spec.McpServerTransport;
import io.modelcontextprotocol.spec.McpServerTransportProvider;
import io.modelcontextprotocol.spec.McpStatelessServerTransport;
import io.modelcontextprotocol.spec.ProtocolVersions;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import reactor.core.publisher.Mono;

/**
 * Real SDK servers without a network: the SDK ships no in-memory transport, so these minimal transports
 * capture the server's request handler (stateless) or session factory (stateful) and drive JSON-RPC
 * messages straight into it. Everything behind the transport (tools/call routing, structured-output
 * validation, result wrapping) is the unmodified SDK code.
 */
final class InMemoryMcp {

    private static final McpJsonMapper MAPPER = McpJsonDefaults.getMapper();

    private InMemoryMcp() {
    }

    static McpSchema.CallToolResult toResult(Object jsonRpcResult) {
        return jsonRpcResult instanceof McpSchema.CallToolResult r ? r
                : MAPPER.convertValue(jsonRpcResult, McpSchema.CallToolResult.class);
    }

    static final class Stateless implements McpStatelessServerTransport {
        private McpStatelessServerHandler handler;
        final McpStatelessSyncServer server;

        Stateless(McpStatelessServerFeatures.SyncToolSpecification... tools) {
            this.server = McpServer.sync(this).serverInfo("test", "0").validateToolInputs(false).tools(tools).build();
        }

        @Override
        public void setMcpHandler(McpStatelessServerHandler handler) {
            this.handler = handler;
        }

        @Override
        public Mono<Void> closeGracefully() {
            return Mono.empty();
        }

        McpSchema.JSONRPCResponse request(String method, Object params, McpTransportContext ctx) {
            return handler.handleRequest(ctx, new McpSchema.JSONRPCRequest(McpSchema.JSONRPC_VERSION, method, "1", params)).block();
        }

        McpSchema.CallToolResult call(String tool, Map<String, Object> args, McpTransportContext ctx) {
            McpSchema.JSONRPCResponse r = request(McpSchema.METHOD_TOOLS_CALL, new McpSchema.CallToolRequest(tool, args), ctx);
            if (r.error() != null) {
                throw new IllegalStateException("JSON-RPC error: " + r.error().message());
            }
            return toResult(r.result());
        }
    }

    static final class Stateful implements McpServerTransportProvider {
        private McpServerSession.Factory factory;
        private McpServerSession session;
        private final List<McpSchema.JSONRPCMessage> sent = new CopyOnWriteArrayList<>();
        final McpSyncServer server;

        Stateful(McpServerFeatures.SyncToolSpecification... tools) {
            this.server = McpServer.sync(this).serverInfo("test", "0").validateToolInputs(false).tools(tools).build();
            this.session = factory.create(new Wire());
            send(McpSchema.METHOD_INITIALIZE, new McpSchema.InitializeRequest(ProtocolVersions.MCP_2025_06_18,
                    McpSchema.ClientCapabilities.builder().build(), new McpSchema.Implementation("client", "0")));
            session.handle(new McpSchema.JSONRPCNotification(McpSchema.JSONRPC_VERSION, McpSchema.METHOD_NOTIFICATION_INITIALIZED, null)).block();
        }

        private McpSchema.JSONRPCResponse send(String method, Object params) {
            String id = "id-" + sent.size();
            session.handle(new McpSchema.JSONRPCRequest(McpSchema.JSONRPC_VERSION, method, id, params)).block();
            return sent.stream().filter(McpSchema.JSONRPCResponse.class::isInstance).map(McpSchema.JSONRPCResponse.class::cast)
                    .filter(r -> id.equals(r.id())).findFirst().orElseThrow();
        }

        McpSchema.CallToolResult call(String tool, Map<String, Object> args) {
            McpSchema.JSONRPCResponse r = send(McpSchema.METHOD_TOOLS_CALL, new McpSchema.CallToolRequest(tool, args));
            if (r.error() != null) {
                throw new IllegalStateException("JSON-RPC error: " + r.error().message());
            }
            return toResult(r.result());
        }

        @Override
        public void setSessionFactory(McpServerSession.Factory factory) {
            this.factory = factory;
        }

        @Override
        public Mono<Void> notifyClients(String method, Object params) {
            return Mono.empty();
        }

        @Override
        public Mono<Void> closeGracefully() {
            return Mono.empty();
        }

        private final class Wire implements McpServerTransport {
            @Override
            public Mono<Void> closeGracefully() {
                return Mono.empty();
            }

            @Override
            public Mono<Void> sendMessage(McpSchema.JSONRPCMessage message) {
                sent.add(message);
                return Mono.empty();
            }

            @Override
            public <T> T unmarshalFrom(Object data, TypeRef<T> typeRef) {
                return MAPPER.convertValue(data, typeRef);
            }
        }
    }
}
