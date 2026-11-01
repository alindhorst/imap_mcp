package de.alexanderlindhorst.imapmcp.transport.http;

import jakarta.servlet.http.HttpServlet;

/** Unauthenticated liveness endpoint (SPEC 5.6). */
public final class HealthServlet extends HttpServlet {

    public void beginShutdown() {
    }
}
