package de.alexanderlindhorst.imapmcp.core.mcp;

import io.modelcontextprotocol.json.McpJsonMapper;
import io.modelcontextprotocol.json.jackson3.JacksonMcpJsonMapper;
import io.modelcontextprotocol.spec.McpError;
import io.modelcontextprotocol.spec.McpSchema.JSONRPCResponse.JSONRPCError;
import java.util.Set;
import tools.jackson.core.JsonGenerator;
import tools.jackson.databind.SerializationContext;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.module.SimpleModule;
import tools.jackson.databind.ser.std.StdSerializer;

/**
 * The SDK's JSON mapper with changes on the way out only (reading is the SDK default, {@code JsonMapper.shared()}, so
 * the client's responses, for example to an elicitation, still parse). The SDK 2.1.0 puts exception text into its
 * error replies: it serialises the {@link McpError} object itself (stack trace, class names, JDK version), builds
 * {@code "Invalid message format: " + e.getMessage()} (Jackson class names, and the whole request body for an
 * unknown message), and puts {@code t.getMessage()} of an internal failure into JSON-RPC error objects. Here:
 * <ul>
 * <li>an {@link McpError} is written as a bare JSON-RPC error and a {@link JSONRPCError} as the bare error object;</li>
 * <li>a message is kept only when every part (split on "; ") is a fixed SDK transport string; otherwise the text is
 * fixed per code ({@code -32700} "Parse error", {@code -32600} "Invalid request", {@code -32601} "Method not found",
 * {@code -32602} "Invalid params", {@code -32603} "Internal error", any other "Error"); {@code -32603} is always
 * "Internal error";</li>
 * <li>{@code data} is kept only as a scalar and never for {@code -32603};</li>
 * <li>any other {@link Throwable} is written as {@code {"message":"Internal error"}} (defence in depth).</li>
 * </ul>
 */
public final class SafeMcpJson {

    static final int INTERNAL_ERROR = -32603;

    /** The fixed strings of the SDK transports that reach a client as an error message (SDK 2.1.0). */
    private static final Set<String> FIXED = Set.of("Invalid message format", "Unknown message type",
            "application/json required in Accept header", "text/event-stream required in Accept header",
            "Both application/json and text/event-stream required in Accept header",
            "Session ID required in mcp-session-id header", "Server is shutting down",
            "The server accepts either requests or notifications",
            "Unsupported Media Type: Content-Type must be application/json");

    private static final String DETAIL_PREFIX = "Invalid message format:";

    private static final McpJsonMapper MAPPER = new JacksonMcpJsonMapper(JsonMapper.shared().rebuild()
            .addModule(new SimpleModule("safe-mcp-error")
                    .addSerializer(Throwable.class, new ThrowableSerializer())
                    .addSerializer(McpError.class, new McpErrorSerializer())
                    .addSerializer(JSONRPCError.class, new JsonRpcErrorSerializer()))
            .build());

    private SafeMcpJson() {
    }

    public static McpJsonMapper mapper() {
        return MAPPER;
    }

    static String safeMessage(int code, String message) {
        if (code != INTERNAL_ERROR && message != null) {
            StringBuilder out = new StringBuilder();
            boolean ok = true;
            for (String part : message.split("; ", -1)) {
                // the SDK appends the exception text of a failed parse after a colon: drop it, keep the fixed part
                String p = part.startsWith(DETAIL_PREFIX) ? "Invalid message format" : part;
                if (!FIXED.contains(p)) {
                    ok = false;
                    break;
                }
                out.append(out.length() == 0 ? "" : "; ").append(p);
            }
            if (ok) {
                return out.toString();
            }
        }
        return switch (code) {
            case -32700 -> "Parse error";
            case -32600 -> "Invalid request";
            case -32601 -> "Method not found";
            case -32602 -> "Invalid params";
            case INTERNAL_ERROR -> "Internal error";
            default -> "Error";
        };
    }

    private static void writeErrorObject(JsonGenerator gen, int code, String message, Object data) {
        gen.writeStartObject();
        gen.writeNumberProperty("code", code);
        gen.writeStringProperty("message", safeMessage(code, message));
        if (code != INTERNAL_ERROR && data != null) {
            if (data instanceof String s) {
                gen.writeStringProperty("data", s);
            } else if (data instanceof Boolean b) {
                gen.writeBooleanProperty("data", b);
            } else if (data instanceof Double || data instanceof Float) {
                gen.writeNumberProperty("data", ((Number) data).doubleValue());
            } else if (data instanceof Number n) {
                gen.writeNumberProperty("data", n.longValue());
            }
        }
        gen.writeEndObject();
    }

    private static final class JsonRpcErrorSerializer extends StdSerializer<JSONRPCError> {

        JsonRpcErrorSerializer() {
            super(JSONRPCError.class);
        }

        @Override
        public void serialize(JSONRPCError value, JsonGenerator gen, SerializationContext ctx) {
            writeErrorObject(gen, value.code(), value.message(), value.data());
        }
    }

    private static final class McpErrorSerializer extends StdSerializer<McpError> {

        McpErrorSerializer() {
            super(McpError.class);
        }

        @Override
        public void serialize(McpError value, JsonGenerator gen, SerializationContext ctx) {
            JSONRPCError error = value.getJsonRpcError();
            gen.writeStartObject();
            gen.writeStringProperty("jsonrpc", "2.0");
            gen.writeName("error");
            writeErrorObject(gen, error.code(), error.message(), error.data());
            gen.writeNullProperty("id");
            gen.writeEndObject();
        }
    }

    private static final class ThrowableSerializer extends StdSerializer<Throwable> {

        ThrowableSerializer() {
            super(Throwable.class);
        }

        @Override
        public void serialize(Throwable value, JsonGenerator gen, SerializationContext ctx) {
            gen.writeStartObject();
            gen.writeStringProperty("message", "Internal error");
            gen.writeEndObject();
        }
    }
}
