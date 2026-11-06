package de.alexanderlindhorst.imapmcp.transport.http;

import com.nimbusds.jose.util.JSONObjectUtils;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * RFC 9728 protected resource metadata (SPEC 5.3), unauthenticated {@code GET}. The document is built once from
 * the configuration; nothing from the request (Host, forwarded headers) enters it. GET and HEAD; other methods get 405.
 */
public final class ProtectedResourceMetadataServlet extends HttpServlet {

    private final String body;

    public ProtectedResourceMetadataServlet(OAuthResourceConfig config) {
        Map<String, Object> doc = new LinkedHashMap<>();
        doc.put("resource", config.resource());
        doc.put("authorization_servers", List.of(config.issuer()));
        if (!config.scopesSupported().isEmpty()) {
            doc.put("scopes_supported", config.scopesSupported());
        }
        doc.put("bearer_methods_supported", List.of("header"));
        this.body = JSONObjectUtils.toJSONString(doc);
    }

    @Override
    protected void service(HttpServletRequest req, HttpServletResponse res) throws IOException {
        boolean head = "HEAD".equals(req.getMethod());
        if (!head && !"GET".equals(req.getMethod())) {
            res.setStatus(HttpServletResponse.SC_METHOD_NOT_ALLOWED);
            res.setHeader("Allow", "GET, HEAD");
            return;
        }
        res.setStatus(HttpServletResponse.SC_OK);
        res.setContentType("application/json");
        res.setCharacterEncoding("UTF-8");
        res.setHeader("Cache-Control", "public, max-age=300");
        if (!head) {
            res.getWriter().write(body);
        }
    }
}
