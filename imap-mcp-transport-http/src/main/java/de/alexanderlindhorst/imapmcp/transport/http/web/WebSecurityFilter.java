package de.alexanderlindhorst.imapmcp.transport.http.web;

import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Locale;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * First filter of {@code /accounts} (SPEC 5.3): the response headers every answer carries ({@code no-store}, a CSP
 * without scripts, {@code frame-ancestors 'none'}), the {@code Host} check against the configured public host
 * (never against forwarded headers) and, for {@code POST}, the {@code Origin} check: when present it must equal the
 * public origin ({@code null} included). Refusals are a fixed 403.
 */
public final class WebSecurityFilter implements Filter {

    static final String CSP = "default-src 'none'; form-action 'self'; base-uri 'none'; frame-ancestors 'none'";
    /** The CSP with an extra {@code form-action} source (an upstream provider's origin, SPEC 5.3 v0.29). */
    static String csp(String extraFormActionSource) {
        return "default-src 'none'; form-action 'self' " + extraFormActionSource
                + "; base-uri 'none'; frame-ancestors 'none'";
    }

    private static final Logger LOG = LoggerFactory.getLogger(WebSecurityFilter.class);

    private final WebConfig config;

    public WebSecurityFilter(WebConfig config) {
        this.config = config;
    }

    /** Sets the headers on a response; also used by the servlet for error answers it writes itself. */
    static void applyHeaders(HttpServletResponse res) {
        res.setHeader("Cache-Control", "no-store");
        res.setHeader("Content-Security-Policy", CSP);
        res.setHeader("X-Content-Type-Options", "nosniff");
        res.setHeader("Referrer-Policy", "no-referrer");
    }

    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
            throws IOException, ServletException {
        HttpServletRequest req = (HttpServletRequest) request;
        HttpServletResponse res = (HttpServletResponse) response;
        applyHeaders(res);
        String host = req.getHeader("Host");
        if (host == null || !host.trim().toLowerCase(Locale.ROOT).equals(config.authority())) {
            LOG.debug("accounts request refused: host");
            AccountsServlet.writePage(res, HttpServletResponse.SC_FORBIDDEN, "Forbidden", "<p>Forbidden.</p>");
            return;
        }
        String method = req.getMethod();
        if (!"GET".equals(method) && !"HEAD".equals(method) && !"POST".equals(method)) {
            res.setHeader("Allow", "GET, HEAD, POST");
            AccountsServlet.writePage(res, HttpServletResponse.SC_METHOD_NOT_ALLOWED, "Method not allowed",
                    "<p>Method not allowed.</p>");
            return;
        }
        if ("POST".equals(method)) {
            String origin = req.getHeader("Origin");
            if (origin != null && !origin.trim().toLowerCase(Locale.ROOT).equals(config.origin())) {
                LOG.debug("accounts request refused: origin");
                AccountsServlet.writePage(res, HttpServletResponse.SC_FORBIDDEN, "Forbidden", "<p>Forbidden.</p>");
                return;
            }
        }
        chain.doFilter(request, response);
    }
}
