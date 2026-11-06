package de.alexanderlindhorst.imapmcp.transport.http;

import io.modelcontextprotocol.common.McpTransportContext;
import io.modelcontextprotocol.json.McpJsonDefaults;
import io.modelcontextprotocol.json.McpJsonMapper;
import io.modelcontextprotocol.server.McpServer;
import io.modelcontextprotocol.server.McpServerFeatures;
import io.modelcontextprotocol.server.McpStatelessServerFeatures;
import io.modelcontextprotocol.server.transport.HttpServletStatelessServerTransport;
import io.modelcontextprotocol.server.transport.HttpServletStreamableServerTransportProvider;
import jakarta.servlet.DispatcherType;
import jakarta.servlet.Filter;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import java.net.InetAddress;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import de.alexanderlindhorst.imapmcp.transport.http.web.WebConfig;
import de.alexanderlindhorst.imapmcp.transport.http.web.WebSurface;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.Executors;
import java.util.function.Consumer;
import java.util.function.Function;
import org.eclipse.jetty.ee10.servlet.FilterHolder;
import org.eclipse.jetty.ee10.servlet.ServletContextHandler;
import org.eclipse.jetty.ee10.servlet.ServletHolder;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.util.thread.QueuedThreadPool;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Embedded Jetty 12 (ee10) serving the SDK's stateless Streamable HTTP servlet at {@code /mcp} (SPEC 2, 5.1,
 * 5.3), or, with {@link Builder#stateful}, the SDK's stateful Streamable HTTP servlet with GET, POST and DELETE
 * and sessions bound to the principal (SPEC 4, v3b). Requests run on virtual threads. Filter chain order on /mcp:
 * Host -> Origin -> method guard (POST only, or GET/POST/DELETE) -> token -> principal context -> session binding (stateful only) ->
 * MCP servlet (the method check precedes authentication; a 405 leaks nothing). The filters cover
 * /mcp only, not /health. The optional health servlet is mounted at /health. Everything else is 404.
 */
public final class HttpLocalServer {

    public static final int MAX_REQUEST_BYTES = 1024 * 1024;
    public static final String MCP_PATH = "/mcp";
    public static final String HEALTH_PATH = "/health";
    /** Upper bound of a form post on the linking surface. */
    static final int WEB_MAX_FORM_BYTES = 64 * 1024;

    private static final Logger LOG = LoggerFactory.getLogger(HttpLocalServer.class);

    private final String bind;
    private final int port;
    private final boolean allowNonLoopbackBind;
    private final int maxRequestBytes;
    private final String serverName;
    private final String serverVersion;
    private final McpJsonMapper jsonMapper;
    private final List<McpStatelessServerFeatures.SyncToolSpecification> tools;
    private final Function<HttpServletRequest, McpTransportContext> contextExtractor;
    private final Filter hostFilter;
    private final Filter originFilter;
    private final Filter bearerFilter;
    private final Filter principalFilter;
    private final HttpServlet healthServlet;
    private final Map<String, HttpServlet> openServlets;
    private final WebSurface webSurface;
    private final boolean stateful;
    private final List<McpServerFeatures.SyncToolSpecification> statefulTools;
    private final Function<HttpServletRequest, String> sessionPrincipal;
    private final Consumer<String> sessionEndListener;
    private final int maxSessionsPerPrincipal;
    private final int maxSessions;
    private final Duration sessionIdle;
    private final Clock sessionClock;
    private final Duration requestTimeout;

    private Server server;
    private ServerConnector connector;
    private Runnable mcpServerCloser = () -> { };
    private SessionRegistry sessionRegistry;

    private HttpLocalServer(Builder b) {
        this.bind = b.bind;
        this.port = b.port;
        this.allowNonLoopbackBind = b.allowNonLoopbackBind;
        this.maxRequestBytes = b.maxRequestBytes;
        this.serverName = b.serverName;
        this.serverVersion = b.serverVersion;
        this.jsonMapper = b.jsonMapper;
        this.tools = List.copyOf(b.tools);
        this.contextExtractor = b.contextExtractor;
        this.hostFilter = b.hostFilter;
        this.originFilter = b.originFilter;
        this.bearerFilter = b.bearerFilter;
        this.principalFilter = b.principalFilter;
        this.healthServlet = b.healthServlet;
        this.openServlets = Map.copyOf(b.openServlets);
        this.webSurface = b.webSurface;
        this.stateful = b.stateful;
        this.statefulTools = List.copyOf(b.statefulTools);
        this.sessionPrincipal = b.sessionPrincipal;
        this.sessionEndListener = b.sessionEndListener;
        this.maxSessionsPerPrincipal = b.maxSessionsPerPrincipal;
        this.maxSessions = b.maxSessions;
        this.sessionIdle = b.sessionIdle;
        this.sessionClock = b.sessionClock;
        this.requestTimeout = b.requestTimeout;
    }

    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder {
        private String bind = "127.0.0.1";
        private int port = 8080;
        private boolean allowNonLoopbackBind;
        private int maxRequestBytes = MAX_REQUEST_BYTES;
        private String serverName = "imap-mcp";
        private String serverVersion = "dev";
        private McpJsonMapper jsonMapper = McpJsonDefaults.getMapper();
        private final List<McpStatelessServerFeatures.SyncToolSpecification> tools = new ArrayList<>();
        private Function<HttpServletRequest, McpTransportContext> contextExtractor = req -> McpTransportContext.EMPTY;
        private Filter hostFilter;
        private Filter originFilter;
        private Filter bearerFilter;
        private Filter principalFilter;
        private HttpServlet healthServlet;
        private final Map<String, HttpServlet> openServlets = new LinkedHashMap<>();
        private WebSurface webSurface;
        private boolean stateful;
        private final List<McpServerFeatures.SyncToolSpecification> statefulTools = new ArrayList<>();
        private Function<HttpServletRequest, String> sessionPrincipal =
                req -> req.getAttribute(BearerTokenFilter.ACCOUNT_ATTRIBUTE) != null ? "local" : null;
        private Consumer<String> sessionEndListener = id -> { };
        private int maxSessionsPerPrincipal = SessionRegistry.MAX_PER_PRINCIPAL;
        private int maxSessions = SessionRegistry.MAX_PER_REPLICA;
        private Duration sessionIdle = SessionRegistry.IDLE_TIMEOUT;
        private Clock sessionClock = Clock.systemUTC();
        private Duration requestTimeout = Duration.ofMinutes(11);

        public Builder bind(String bind) {
            this.bind = Objects.requireNonNull(bind, "bind");
            return this;
        }

        public Builder port(int port) {
            this.port = port;
            return this;
        }

        public Builder allowNonLoopbackBind(boolean allow) {
            this.allowNonLoopbackBind = allow;
            return this;
        }

        /** Upper bound of a request body; defaults to {@link #MAX_REQUEST_BYTES}. */
        public Builder maxRequestBytes(int bytes) {
            if (bytes < 1) {
                throw new IllegalArgumentException("maxRequestBytes must be positive");
            }
            this.maxRequestBytes = bytes;
            return this;
        }

        /** Name and version announced in the MCP initialize response. */
        public Builder serverInfo(String name, String version) {
            this.serverName = Objects.requireNonNull(name, "name");
            this.serverVersion = Objects.requireNonNull(version, "version");
            return this;
        }

        public Builder jsonMapper(McpJsonMapper mapper) {
            this.jsonMapper = Objects.requireNonNull(mapper, "mapper");
            return this;
        }

        public Builder tool(McpStatelessServerFeatures.SyncToolSpecification spec) {
            this.tools.add(Objects.requireNonNull(spec, "spec"));
            return this;
        }

        /** Runs on the request thread; later reads the AccountContext set by the bearer filter. */
        public Builder contextExtractor(Function<HttpServletRequest, McpTransportContext> extractor) {
            this.contextExtractor = Objects.requireNonNull(extractor, "extractor");
            return this;
        }

        public Builder hostFilter(Filter filter) {
            this.hostFilter = filter;
            return this;
        }

        public Builder originFilter(Filter filter) {
            this.originFilter = filter;
            return this;
        }

        public Builder bearerFilter(Filter filter) {
            this.bearerFilter = filter;
            return this;
        }

        /** http-public: runs after the token check, maps the validated principal to the AccountContext. */
        public Builder principalFilter(Filter filter) {
            this.principalFilter = filter;
            return this;
        }

        public Builder healthServlet(HttpServlet servlet) {
            this.healthServlet = servlet;
            return this;
        }

        /**
         * An unauthenticated servlet outside /mcp (no Host/Origin/token filters), like /health; used for the
         * RFC 9728 metadata of {@code http-public}; restricted to paths under /.well-known/.
         */
        public Builder openServlet(String path, HttpServlet servlet) {
            if (path == null || !path.startsWith("/.well-known/") || path.length() == "/.well-known/".length()) {
                throw new IllegalArgumentException("an unauthenticated servlet may only live under /.well-known/");
            }
            this.openServlets.put(Objects.requireNonNull(path, "path"), Objects.requireNonNull(servlet, "servlet"));
            return this;
        }

        /**
         * The linking surface of {@code http-public}, mounted at {@code /accounts} with its own filters (no
         * bearer token, none of the {@code /mcp} filters).
         */
        public Builder webSurface(WebSurface surface) {
            this.webSurface = surface;
            return this;
        }

        /**
         * Serves the stateful Streamable HTTP transport (needed for server-to-client requests such as the purge
         * confirmation) instead of the stateless one; tools then come from {@link #statefulTool}.
         */
        public Builder stateful(boolean stateful) {
            this.stateful = stateful;
            return this;
        }

        public Builder statefulTool(McpServerFeatures.SyncToolSpecification spec) {
            this.statefulTools.add(Objects.requireNonNull(spec, "spec"));
            return this;
        }

        /**
         * The validated principal of a request (stateful mode): sessions are bound to it. It must identify the
         * authenticated principal, never the tenant: {@code http-local} has the one fixed principal (the default,
         * present only for an authenticated request); {@code http-public} must return issuer and subject of the
         * validated JWT principal ({@code JwtTokenFilter.PRINCIPAL_ATTRIBUTE}), because unknown principals share one
         * sentinel tenant and would otherwise share sessions and the caps. Null means "none": 404.
         */
        public Builder sessionPrincipal(Function<HttpServletRequest, String> principal) {
            this.sessionPrincipal = Objects.requireNonNull(principal, "principal");
            return this;
        }

        /** Called with the id of every session that ends (eviction, idle, DELETE); must not block. */
        public Builder sessionEndListener(Consumer<String> listener) {
            this.sessionEndListener = Objects.requireNonNull(listener, "listener");
            return this;
        }

        /** Defaults: 10 per principal, 1000 per replica, 30 minutes idle (SPEC 4, v3b); settable for tests. */
        public Builder sessionLimits(int perPrincipal, int perReplica, Duration idle) {
            this.maxSessionsPerPrincipal = perPrincipal;
            this.maxSessions = perReplica;
            this.sessionIdle = Objects.requireNonNull(idle, "idle");
            return this;
        }

        public Builder sessionClock(Clock clock) {
            this.sessionClock = Objects.requireNonNull(clock, "clock");
            return this;
        }

        /** Server-to-client request timeout of the stateful server; above the longest tool deadline (SPEC 5.5). */
        public Builder requestTimeout(Duration timeout) {
            this.requestTimeout = Objects.requireNonNull(timeout, "timeout");
            return this;
        }

        public HttpLocalServer build() {
            // http-public wiring invariant (SPEC 5.3): a token filter that validates JWTs without the filter that
            // maps the principal to a tenant would leave the AccountContext unset, and the reverse is meaningless
            if (bearerFilter instanceof JwtTokenFilter && principalFilter == null) {
                throw new IllegalStateException("a JwtTokenFilter needs a principalFilter");
            }
            if (principalFilter != null && bearerFilter == null) {
                throw new IllegalStateException("a principalFilter needs a token filter before it");
            }
            return new HttpLocalServer(this);
        }
    }

    /** Fails with {@link IllegalStateException} when the bind policy refuses the address. */
    public synchronized void start() throws Exception {
        InetAddress addr = BindPolicy.check(bind, allowNonLoopbackBind);
        if (!addr.isLoopbackAddress()) {
            LOG.warn("http.bind {} is not a loopback address (http.allowNonLoopbackBind=true); restrict exposure at "
                    + "the host/network level", bind);
        }
        HttpServlet mcpServlet;
        SessionBindingFilter sessionFilter = null;
        if (stateful) {
            HttpServletStreamableServerTransportProvider transport = HttpServletStreamableServerTransportProvider
                    .builder().jsonMapper(jsonMapper).mcpEndpoint(MCP_PATH).maxRequestSize(maxRequestBytes)
                    .contextExtractor(contextExtractor::apply).build();
            // No immediateExecution: a tool call waits for the user's answer, which arrives as another request.
            var mcp = McpServer.sync(transport).serverInfo(serverName, serverVersion).validateToolInputs(false)
                    .requestTimeout(requestTimeout).tools(statefulTools).build();
            mcpServerCloser = mcp::closeGracefully;
            mcpServlet = transport;
            sessionRegistry = new SessionRegistry(maxSessionsPerPrincipal, maxSessions, sessionIdle, sessionClock,
                    sessionEndListener);
            sessionFilter = new SessionBindingFilter(sessionRegistry, sessionPrincipal);
        } else {
            HttpServletStatelessServerTransport transport = HttpServletStatelessServerTransport.builder()
                    .jsonMapper(jsonMapper).messageEndpoint(MCP_PATH).maxRequestSize(maxRequestBytes)
                    .contextExtractor(contextExtractor::apply).build();
            // validateToolInputs(false): input validation is ours (ValidatingToolHandler), the SDK's error shape differs.
            var mcp = McpServer.sync(transport).serverInfo(serverName, serverVersion).immediateExecution(true)
                    .validateToolInputs(false).tools(tools).build();
            mcpServerCloser = mcp::closeGracefully;
            mcpServlet = transport;
        }

        QueuedThreadPool pool = new QueuedThreadPool();
        pool.setName("http-local");
        pool.setVirtualThreadsExecutor(Executors.newVirtualThreadPerTaskExecutor());
        server = new Server(pool);
        connector = new ServerConnector(server);
        connector.setHost(bind);
        connector.setPort(port);
        server.addConnector(connector);

        ServletContextHandler ctx = new ServletContextHandler();
        ctx.setContextPath("/");
        addFilter(ctx, hostFilter, MCP_PATH);
        addFilter(ctx, originFilter, MCP_PATH);
        addFilter(ctx, stateful ? AllowedMethodsFilter.STREAMABLE : AllowedMethodsFilter.POST_ONLY, MCP_PATH);
        addFilter(ctx, bearerFilter, MCP_PATH);
        addFilter(ctx, principalFilter, MCP_PATH);
        addFilter(ctx, sessionFilter, MCP_PATH); // after the token and the principal: the session binds to the principal
        ctx.addServlet(new ServletHolder(mcpServlet), MCP_PATH);
        if (healthServlet != null) {
            ctx.addServlet(new ServletHolder(healthServlet), HEALTH_PATH);
        }
        openServlets.forEach((path, servlet) -> ctx.addServlet(new ServletHolder(servlet), path));
        if (webSurface != null) {
            ctx.setMaxFormContentSize(WEB_MAX_FORM_BYTES);
            for (Filter f : webSurface.filters()) {
                addFilter(ctx, f, WebConfig.BASE_PATH + "/*");
            }
            ctx.addServlet(new ServletHolder(webSurface.servlet()), WebConfig.BASE_PATH + "/*");
        }
        ctx.addServlet(new ServletHolder(new NotFoundServlet()), "/");
        server.setHandler(ctx);
        server.setStopTimeout(5000);
        server.start();
    }

    private static void addFilter(ServletContextHandler ctx, Filter filter, String path) {
        if (filter != null) {
            ctx.addFilter(new FilterHolder(filter), path, EnumSet.of(DispatcherType.REQUEST));
        }
    }

    /** Flips /health to 503 shutting_down; call before {@link #stop()} so orchestrators see it. Idempotent. */
    public void beginShutdown() {
        if (healthServlet instanceof HealthServlet h) {
            h.beginShutdown();
        }
    }

    /** Graceful: stops accepting, lets in-flight requests finish (bounded), then closes the MCP server. */
    public synchronized void stop() throws Exception {
        beginShutdown();
        if (server != null) {
            server.stop();
        }
        mcpServerCloser.run();
    }

    /** Number of live sessions (stateful mode; 0 otherwise). */
    int sessionCount() {
        return sessionRegistry == null ? 0 : sessionRegistry.size();
    }

    /** The actually bound port (useful with port 0). */
    public int port() {
        return connector.getLocalPort();
    }

    public String host() {
        return connector.getHost();
    }
}
