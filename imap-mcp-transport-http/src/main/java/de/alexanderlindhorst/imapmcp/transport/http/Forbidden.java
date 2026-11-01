package de.alexanderlindhorst.imapmcp.transport.http;

import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

/** The one 403 answer of the Host/Origin filters: fixed JSON-RPC error body, never a reason, no CORS headers. */
final class Forbidden {

    static final String BODY = "{\"jsonrpc\":\"2.0\",\"error\":{\"code\":-32000,\"message\":\"Forbidden\"},\"id\":null}";

    private Forbidden() {
    }

    static void send(HttpServletResponse response) throws IOException {
        byte[] bytes = BODY.getBytes(StandardCharsets.UTF_8);
        response.setStatus(HttpServletResponse.SC_FORBIDDEN);
        response.setContentType("application/json");
        response.setCharacterEncoding("UTF-8");
        response.setContentLength(bytes.length);
        response.getOutputStream().write(bytes);
    }
}
