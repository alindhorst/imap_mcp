package de.alexanderlindhorst.imapmcp.transport.http.web;

import de.alexanderlindhorst.imapmcp.core.web.WebSession;
import de.alexanderlindhorst.imapmcp.core.web.WebSessionStore;
import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Second filter of {@code /accounts}: everything except the login and callback endpoints (and the upstream OAuth
 * callback {@code /accounts/oauth/<id>/callback}, which is bound by its own cookie) needs a valid server-side
 * session (cookie {@value #COOKIE}). Without one a {@code GET} is sent to the login, anything else is a 403. A
 * {@code POST} also needs the session's CSRF token in the form field {@code csrf} (constant-time comparison). The
 * session is handed to the servlet as a request attribute; a cookie that appears twice counts as absent.
 */
public final class WebSessionFilter implements Filter {

    public static final String COOKIE = "__Host-session";
    public static final String CSRF_FIELD = "csrf";
    static final String LOGIN_PATH = WebConfig.BASE_PATH + "/login";
    static final String CALLBACK_PATH = WebConfig.BASE_PATH + "/callback";
    private static final Logger LOG = LoggerFactory.getLogger(WebSessionFilter.class);

    private final WebSessionStore sessions;

    public WebSessionFilter(WebSessionStore sessions) {
        this.sessions = sessions;
    }

    /** The value of the cookie {@code name}, or null when it is absent or sent more than once. */
    static String cookie(HttpServletRequest req, String name) {
        List<String> values = new ArrayList<>();
        java.util.Enumeration<String> headers = req.getHeaders("Cookie");
        while (headers != null && headers.hasMoreElements()) {
            for (String part : headers.nextElement().split(";")) {
                String p = part.trim();
                int eq = p.indexOf('=');
                if (eq > 0 && p.substring(0, eq).equals(name)) {
                    values.add(p.substring(eq + 1));
                }
            }
        }
        return values.size() == 1 ? values.get(0) : null;
    }

    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
            throws IOException, ServletException {
        HttpServletRequest req = (HttpServletRequest) request;
        HttpServletResponse res = (HttpServletResponse) response;
        String path = req.getRequestURI();
        if (LOGIN_PATH.equals(path) || CALLBACK_PATH.equals(path) || UpstreamPages.isCallbackPath(path)) {
            chain.doFilter(request, response);
            return;
        }
        String id = cookie(req, COOKIE);
        Optional<WebSession> session = id == null ? Optional.empty() : sessions.find(id);
        if (session.isEmpty()) {
            if ("GET".equals(req.getMethod()) || "HEAD".equals(req.getMethod())) {
                res.setStatus(HttpServletResponse.SC_FOUND);
                res.setHeader("Location", LOGIN_PATH);
            } else {
                AccountsServlet.writePage(res, HttpServletResponse.SC_FORBIDDEN, "Forbidden", "<p>Forbidden.</p>");
            }
            return;
        }
        if ("POST".equals(req.getMethod())) {
            // the token comes from the form body only: a POST with a query string is refused altogether
            String token = req.getQueryString() != null ? null : req.getParameter(CSRF_FIELD);
            if (token == null || !MessageDigest.isEqual(token.getBytes(StandardCharsets.UTF_8),
                    session.get().csrfToken().getBytes(StandardCharsets.UTF_8))) {
                LOG.debug("accounts request refused: csrf");
                AccountsServlet.writePage(res, HttpServletResponse.SC_FORBIDDEN, "Forbidden", "<p>Forbidden.</p>");
                return;
            }
        }
        req.setAttribute(WebSurface.SESSION_ATTRIBUTE, session.get());
        chain.doFilter(request, response);
    }
}
