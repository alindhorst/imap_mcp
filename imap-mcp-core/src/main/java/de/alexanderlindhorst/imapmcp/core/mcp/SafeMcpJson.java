package de.alexanderlindhorst.imapmcp.core.mcp;

import io.modelcontextprotocol.json.McpJsonMapper;
import io.modelcontextprotocol.json.jackson3.JacksonMcpJsonMapper;
import io.modelcontextprotocol.spec.McpError;
import tools.jackson.core.JsonGenerator;
import tools.jackson.databind.SerializationContext;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.module.SimpleModule;
import tools.jackson.databind.ser.std.StdSerializer;

/**
 * The SDK's JSON mapper with one change: an {@link McpError} is written as a bare JSON-RPC error. The SDK 2.1.0
 * serialises the exception object itself on its error paths, which puts the stack trace (class names, line numbers,
 * the JDK version) into the response body. Code {@code -32603} gets a fixed message, because the original may carry
 * internals. Everything else behaves as the SDK default ({@code JsonMapper.shared()}).
 */
public final class SafeMcpJson {

    static final int INTERNAL_ERROR = -32603;

    private static final McpJsonMapper MAPPER = new JacksonMcpJsonMapper(JsonMapper.shared().rebuild()
            .addModule(new SimpleModule("safe-mcp-error").addSerializer(McpError.class, new McpErrorSerializer()))
            .build());

    private SafeMcpJson() {
    }

    public static McpJsonMapper mapper() {
        return MAPPER;
    }

    private static final class McpErrorSerializer extends StdSerializer<McpError> {

        McpErrorSerializer() {
            super(McpError.class);
        }

        @Override
        public void serialize(McpError value, JsonGenerator gen, SerializationContext ctx) {
            var error = value.getJsonRpcError();
            int code = error == null ? INTERNAL_ERROR : error.code();
            String message = error == null || code == INTERNAL_ERROR || error.message() == null ? null
                    : error.message();
            gen.writeStartObject();
            gen.writeStringProperty("jsonrpc", "2.0");
            gen.writeObjectPropertyStart("error");
            gen.writeNumberProperty("code", code);
            gen.writeStringProperty("message", message == null ? "Internal error" : message);
            gen.writeEndObject();
            gen.writeNullProperty("id");
            gen.writeEndObject();
        }
    }
}
