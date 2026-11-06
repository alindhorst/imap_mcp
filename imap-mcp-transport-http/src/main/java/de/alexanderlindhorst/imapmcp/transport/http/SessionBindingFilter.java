package de.alexanderlindhorst.imapmcp.transport.http;

import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpServletResponseWrapper;
import java.io.IOException;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;

/**
 * Binds every MCP session of the stateful transport to the principal it was issued to (SPEC 4, v3b). Runs after
 * authentication, so each request carries its own valid credentials; the session id is never a credential.
 * A request that names a session unknown to the {@link SessionRegistry}, or bound to another principal, gets the same
 * {@code 404} in both cases and never reaches the SDK. A request without a session id (an {@code initialize}) first
 * reserves room (replica cap: {@code 503} with {@code Retry-After}); the session id the SDK puts on the response is
 * recorded through a response wrapper before the header is committed. A {@code DELETE} ends the session in the
 * registry whatever the SDK answers.
 */
final class SessionBindingFilter implements Filter {

    static final String SESSION_HEADER = "Mcp-Session-Id";
    static final String NOT_FOUND_BODY =
            "{\"jsonrpc\":\"2.0\",\"error\":{\"code\":-32001,\"message\":\"Session not found\"},\"id\":null}";
    private static final String RETRY_AFTER_SECONDS = "30";

    private final SessionRegistry registry;
    private final Function<HttpServletRequest, String> principalOf;

    SessionBindingFilter(SessionRegistry registry, Function<HttpServletRequest, String> principalOf) {
        this.registry = Objects.requireNonNull(registry, "registry");
        this.principalOf = Objects.requireNonNull(principalOf, "principalOf");
    }

    @Override
    public void doFilter(ServletRequest req, ServletResponse res, FilterChain chain)
            throws IOException, ServletException {
        HttpServletRequest request = (HttpServletRequest) req;
        HttpServletResponse response = (HttpServletResponse) res;
        String principal = principalOf.apply(request);
        List<String> ids = Collections.list(request.getHeaders(SESSION_HEADER));
        if (principal == null || ids.size() > 1) {
            notFound(response);
            return;
        }
        if (!ids.isEmpty()) {
            String id = ids.get(0);
            if (!registry.touch(id, principal)) {
                notFound(response);
                return;
            }
            try {
                chain.doFilter(req, res);
            } finally {
                if ("DELETE".equals(request.getMethod())) {
                    registry.remove(id);
                }
            }
            return;
        }
        if (!"POST".equals(request.getMethod())) {
            chain.doFilter(req, res); // the SDK refuses a GET/DELETE without a session itself
            return;
        }
        if (!registry.tryReserve()) {
            response.setStatus(HttpServletResponse.SC_SERVICE_UNAVAILABLE);
            response.setHeader("Retry-After", RETRY_AFTER_SECONDS);
            return;
        }
        RegisteringResponse wrapped = new RegisteringResponse(response, registry, principal);
        try {
            chain.doFilter(req, wrapped);
        } finally {
            wrapped.finish();
        }
    }

    private static void notFound(HttpServletResponse res) throws IOException {
        res.setStatus(HttpServletResponse.SC_NOT_FOUND);
        res.setContentType("application/json");
        res.setCharacterEncoding("UTF-8");
        res.getWriter().write(NOT_FOUND_BODY);
    }

    /** Records the session id the SDK sets on the {@code initialize} response, and gives back an unused reservation. */
    private static final class RegisteringResponse extends HttpServletResponseWrapper {
        private final SessionRegistry registry;
        private final String principal;
        private final AtomicBoolean settled = new AtomicBoolean();

        RegisteringResponse(HttpServletResponse response, SessionRegistry registry, String principal) {
            super(response);
            this.registry = registry;
            this.principal = principal;
        }

        @Override
        public void setHeader(String name, String value) {
            capture(name, value);
            super.setHeader(name, value);
        }

        @Override
        public void addHeader(String name, String value) {
            capture(name, value);
            super.addHeader(name, value);
        }

        private void capture(String name, String value) {
            if (SESSION_HEADER.equalsIgnoreCase(name) && value != null && settled.compareAndSet(false, true)) {
                registry.register(value, principal);
            }
        }

        void finish() {
            if (settled.compareAndSet(false, true)) {
                registry.release();
            }
        }
    }
}
