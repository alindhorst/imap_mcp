package de.alexanderlindhorst.imapmcp.transport.http;

import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;

/**
 * Unauthenticated liveness endpoint (SPEC 5.6). Touches nothing but its own flag: no IMAP, no pool, and the
 * body carries no account, version or pool data. GET only; other methods get 405.
 */
public final class HealthServlet extends HttpServlet {

    private static final String OK = "{\"status\":\"ok\"}";
    private static final String SHUTTING_DOWN = "{\"status\":\"shutting_down\"}";

    private volatile boolean shuttingDown;

    /** From now on /health answers 503; irreversible. */
    public void beginShutdown() {
        shuttingDown = true;
    }

    @Override
    protected void doGet(HttpServletRequest req, HttpServletResponse res) throws IOException {
        res.setStatus(shuttingDown ? HttpServletResponse.SC_SERVICE_UNAVAILABLE : HttpServletResponse.SC_OK);
        res.setContentType("application/json");
        res.setCharacterEncoding("UTF-8");
        res.getWriter().write(shuttingDown ? SHUTTING_DOWN : OK);
    }

    @Override
    protected void service(HttpServletRequest req, HttpServletResponse res) throws IOException {
        if ("GET".equals(req.getMethod())) {
            doGet(req, res);
            return;
        }
        res.setStatus(HttpServletResponse.SC_METHOD_NOT_ALLOWED);
        res.setHeader("Allow", "GET");
    }
}
