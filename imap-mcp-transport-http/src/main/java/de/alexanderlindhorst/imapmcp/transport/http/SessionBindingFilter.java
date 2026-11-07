package de.alexanderlindhorst.imapmcp.transport.http;

import de.alexanderlindhorst.imapmcp.core.metrics.MetricsRecorder;
import de.alexanderlindhorst.imapmcp.core.tenancy.AccountContext;
import io.modelcontextprotocol.json.McpJsonMapper;
import jakarta.servlet.AsyncContext;
import jakarta.servlet.AsyncEvent;
import jakarta.servlet.AsyncListener;
import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpServletResponseWrapper;
import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
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
 * <p>
 * D12 (v0.23): with a rate limiter, a request without a session id takes one token before the reservation, and
 * without one gets {@code 429} (a token stays consumed when the reservation then fails). A {@code POST} that carries a
 * session id and whose JSON-RPC method is {@code initialize} (also in a batch) is refused with {@code 400}, because
 * the SDK would start a new session for it; the body is buffered (at most {@code maxRequestBytes}) and replayed
 * unchanged to the SDK for every other request.
 */
final class SessionBindingFilter implements Filter {

    static final String SESSION_HEADER = "Mcp-Session-Id";
    static final String NOT_FOUND_BODY =
            "{\"jsonrpc\":\"2.0\",\"error\":{\"code\":-32001,\"message\":\"Session not found\"},\"id\":null}";
    static final String RATE_LIMITED_BODY =
            "{\"jsonrpc\":\"2.0\",\"error\":{\"code\":-32003,\"message\":\"Too many requests\"},\"id\":null}";
    static final String INVALID_REQUEST_BODY =
            "{\"jsonrpc\":\"2.0\",\"error\":{\"code\":-32600,\"message\":\"Invalid Request\"},\"id\":null}";
    private static final String RETRY_AFTER_SECONDS = "30";

    private final SessionRegistry registry;
    private final Function<HttpServletRequest, String> principalOf;
    private final InitializeRateLimiter limiter; // null: no rate limit
    private final MetricsRecorder metrics;
    private final McpJsonMapper jsonMapper;
    private final int maxRequestBytes;

    SessionBindingFilter(SessionRegistry registry, Function<HttpServletRequest, String> principalOf,
            InitializeRateLimiter limiter, MetricsRecorder metrics, McpJsonMapper jsonMapper, int maxRequestBytes) {
        this.registry = Objects.requireNonNull(registry, "registry");
        this.principalOf = Objects.requireNonNull(principalOf, "principalOf");
        this.limiter = limiter;
        this.metrics = Objects.requireNonNull(metrics, "metrics");
        this.jsonMapper = Objects.requireNonNull(jsonMapper, "jsonMapper");
        this.maxRequestBytes = maxRequestBytes;
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
            ServletRequest in = req;
            if ("POST".equals(request.getMethod())) {
                if (!utf8(request.getCharacterEncoding())) {
                    response.setStatus(HttpServletResponse.SC_UNSUPPORTED_MEDIA_TYPE); // JSON-RPC bodies are UTF-8
                    return;
                }
                byte[] body = readBounded(request);
                if (body == null) {
                    response.setStatus(HttpServletResponse.SC_REQUEST_ENTITY_TOO_LARGE);
                    return;
                }
                String text = decodeUtf8(body);
                if (text == null) {
                    // not valid UTF-8: Jackson would reject it, but the SDK may decode leniently, so it is not passed on
                    response.setStatus(HttpServletResponse.SC_BAD_REQUEST);
                    return;
                }
                if (carriesInitialize(text)) {
                    // the SDK would start a new session for it, and only our registry entry could be ended again
                    response.setStatus(HttpServletResponse.SC_BAD_REQUEST);
                    response.setContentType("application/json");
                    response.setCharacterEncoding("UTF-8");
                    response.getWriter().write(INVALID_REQUEST_BODY);
                    return;
                }
                in = new ReplayedRequest(request, body);
            }
            // safety net: a new session id the SDK puts on the response is bound and counted, whatever the request
            ServletResponse out = "POST".equals(request.getMethod())
                    ? new RegisteringResponse(response, registry, principal, id) : res;
            try {
                chain.doFilter(in, out);
            } finally {
                if ("DELETE".equals(request.getMethod())) {
                    registry.remove(id);
                } else if (request.isAsyncStarted()) {
                    bindStream(request, id);
                }
            }
            return;
        }
        if (!"POST".equals(request.getMethod())) {
            chain.doFilter(req, res); // the SDK refuses a GET/DELETE without a session itself
            return;
        }
        if (limiter != null) {
            long retryAfter = limiter.tryAcquire(principal);
            if (retryAfter > 0) {
                if (request.getAttribute(BearerTokenFilter.ACCOUNT_ATTRIBUTE) instanceof AccountContext ctx) {
                    metrics.initializeRateLimited(ctx.tenantId());
                }
                response.setStatus(429);
                response.setHeader("Retry-After", Long.toString(retryAfter));
                response.setContentType("application/json");
                response.setCharacterEncoding("UTF-8");
                response.getWriter().write(RATE_LIMITED_BODY);
                return;
            }
        }
        if (!registry.tryReserve(principal)) {
            response.setStatus(HttpServletResponse.SC_SERVICE_UNAVAILABLE);
            response.setHeader("Retry-After", RETRY_AFTER_SECONDS);
            return;
        }
        RegisteringResponse wrapped = new RegisteringResponse(response, registry, principal, null);
        try {
            chain.doFilter(req, wrapped);
        } finally {
            wrapped.finish();
        }
    }

    /**
     * The SDK keeps an SSE stream open without timeout; it is closed with its session (eviction, idle, DELETE) or
     * forgotten when it finishes by itself.
     */
    private void bindStream(HttpServletRequest request, String sessionId) {
        AsyncContext async = request.getAsyncContext();
        Runnable closer = async::complete;
        registry.attachStream(sessionId, closer);
        async.addListener(new AsyncListener() {
            @Override
            public void onComplete(AsyncEvent event) {
                registry.detachStream(sessionId, closer);
            }

            @Override
            public void onTimeout(AsyncEvent event) {
                registry.detachStream(sessionId, closer);
            }

            @Override
            public void onError(AsyncEvent event) {
                registry.detachStream(sessionId, closer);
            }

            @Override
            public void onStartAsync(AsyncEvent event) {
                // a re-dispatch keeps the same stream
            }
        });
    }

    /** The whole body, or null when it exceeds {@code maxRequestBytes}. */
    private byte[] readBounded(HttpServletRequest request) throws IOException {
        try (InputStream in = request.getInputStream()) {
            byte[] body = in.readNBytes((int) Math.min((long) maxRequestBytes + 1, Integer.MAX_VALUE - 8));
            return body.length > maxRequestBytes ? null : body;
        }
    }

    private static boolean utf8(String encoding) {
        return encoding == null || "UTF-8".equalsIgnoreCase(encoding);
    }

    /** Strict decoding; null when the bytes are not valid UTF-8. */
    private static String decodeUtf8(byte[] body) {
        try {
            return StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(body)).toString();
        } catch (CharacterCodingException e) {
            return null;
        }
    }

    /** True when the body is a JSON-RPC message, or a batch with a message, whose method is {@code initialize}. */
    private boolean carriesInitialize(String body) {
        Object parsed;
        try {
            parsed = jsonMapper.readValue(body, Object.class);
        } catch (IOException | RuntimeException e) {
            return false; // the SDK answers a body that does not parse
        }
        if (parsed instanceof List<?> batch) {
            return batch.stream().anyMatch(SessionBindingFilter::isInitialize);
        }
        return isInitialize(parsed);
    }

    private static boolean isInitialize(Object message) {
        return message instanceof java.util.Map<?, ?> m && "initialize".equals(m.get("method"));
    }

    /** The request with its already consumed body served again, unchanged. */
    private static final class ReplayedRequest extends HttpServletRequestWrapper {
        private final byte[] body;

        ReplayedRequest(HttpServletRequest request, byte[] body) {
            super(request);
            this.body = body;
        }

        @Override
        public ServletInputStream getInputStream() {
            ByteArrayInputStream bytes = new ByteArrayInputStream(body);
            return new ServletInputStream() {
                @Override
                public int read() {
                    return bytes.read();
                }

                @Override
                public int read(byte[] b, int off, int len) {
                    return bytes.read(b, off, len);
                }

                @Override
                public boolean isFinished() {
                    return bytes.available() == 0;
                }

                @Override
                public boolean isReady() {
                    return true;
                }

                @Override
                public void setReadListener(ReadListener listener) {
                    throw new IllegalStateException("blocking reads only");
                }
            };
        }

        @Override
        public String getCharacterEncoding() {
            return "UTF-8"; // what was validated above, also when the request named none
        }

        @Override
        public BufferedReader getReader() {
            return new BufferedReader(new InputStreamReader(new ByteArrayInputStream(body), StandardCharsets.UTF_8));
        }
    }

    private static void notFound(HttpServletResponse res) throws IOException {
        res.setStatus(HttpServletResponse.SC_NOT_FOUND);
        res.setContentType("application/json");
        res.setCharacterEncoding("UTF-8");
        res.getWriter().write(NOT_FOUND_BODY);
    }

    /** Turns the reservation into the session whose id the SDK sets on the response, or gives it back unused. */
    private static final class RegisteringResponse extends HttpServletResponseWrapper {
        private final SessionRegistry registry;
        private final String principal;
        private final String knownSessionId; // the id the request carried, or null (a reserved initialize)
        private final AtomicBoolean settled = new AtomicBoolean();

        RegisteringResponse(HttpServletResponse response, SessionRegistry registry, String principal,
                String knownSessionId) {
            super(response);
            this.registry = registry;
            this.principal = principal;
            this.knownSessionId = knownSessionId;
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
            if (SESSION_HEADER.equalsIgnoreCase(name) && value != null && !value.equals(knownSessionId)
                    && settled.compareAndSet(false, true)) {
                if (knownSessionId == null) {
                    registry.register(value, principal);
                } else {
                    registry.registerUnreserved(value, principal);
                }
            }
        }

        void finish() {
            if (knownSessionId == null && settled.compareAndSet(false, true)) {
                registry.release(principal);
            }
        }
    }
}
