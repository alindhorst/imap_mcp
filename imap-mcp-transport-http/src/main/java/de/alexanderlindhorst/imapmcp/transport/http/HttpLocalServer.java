package de.alexanderlindhorst.imapmcp.transport.http;

import io.modelcontextprotocol.common.McpTransportContext;
import io.modelcontextprotocol.json.McpJsonDefaults;
import io.modelcontextprotocol.json.McpJsonMapper;
import io.modelcontextprotocol.server.McpServer;
import io.modelcontextprotocol.server.McpStatelessServerFeatures;
import io.modelcontextprotocol.server.McpStatelessSyncServer;
import io.modelcontextprotocol.server.transport.HttpServletStatelessServerTransport;
import jakarta.servlet.DispatcherType;
import jakarta.servlet.Filter;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import java.net.InetAddress;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.Executors;
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
 * 5.3). Requests run on virtual threads. Filter chain order on /mcp: Host -> Origin -> POST-only guard ->
 * bearer -> MCP servlet (the method check precedes authentication; a 405 leaks nothing). The filters cover
 * /mcp only, not /health. The optional health servlet is mounted at /health. Everything else is 404.
 */
public final class HttpLocalServer {

    public static final int MAX_REQUEST_BYTES = 1024 * 1024;
    public static final String MCP_PATH = "/mcp";
    public static final String HEALTH_PATH = "/health";

    private static final Logger LOG = LoggerFactory.getLogger(HttpLocalServer.class);

    private final String bind;
    private final int port;
    private final boolean allowNonLoopbackBind;
    private final McpJsonMapper jsonMapper;
    private final List<McpStatelessServerFeatures.SyncToolSpecification> tools;
    private final Function<HttpServletRequest, McpTransportContext> contextExtractor;
    private final Filter hostFilter;
    private final Filter originFilter;
    private final Filter bearerFilter;
    private final HttpServlet healthServlet;

    private Server server;
    private ServerConnector connector;
    private McpStatelessSyncServer mcpServer;

    private HttpLocalServer(Builder b) {
        this.bind = b.bind;
        this.port = b.port;
        this.allowNonLoopbackBind = b.allowNonLoopbackBind;
        this.jsonMapper = b.jsonMapper;
        this.tools = List.copyOf(b.tools);
        this.contextExtractor = b.contextExtractor;
        this.hostFilter = b.hostFilter;
        this.originFilter = b.originFilter;
        this.bearerFilter = b.bearerFilter;
        this.healthServlet = b.healthServlet;
    }

    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder {
        private String bind = "127.0.0.1";
        private int port = 8080;
        private boolean allowNonLoopbackBind;
        private McpJsonMapper jsonMapper = McpJsonDefaults.getMapper();
        private final List<McpStatelessServerFeatures.SyncToolSpecification> tools = new ArrayList<>();
        private Function<HttpServletRequest, McpTransportContext> contextExtractor = req -> McpTransportContext.EMPTY;
        private Filter hostFilter;
        private Filter originFilter;
        private Filter bearerFilter;
        private HttpServlet healthServlet;

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

        public Builder healthServlet(HttpServlet servlet) {
            this.healthServlet = servlet;
            return this;
        }

        public HttpLocalServer build() {
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
        HttpServletStatelessServerTransport transport = HttpServletStatelessServerTransport.builder()
                .jsonMapper(jsonMapper).messageEndpoint(MCP_PATH).maxRequestSize(MAX_REQUEST_BYTES)
                .contextExtractor(contextExtractor::apply).build();
        // validateToolInputs(false): input validation is ours (ValidatingToolHandler), the SDK's error shape differs.
        mcpServer = McpServer.sync(transport).serverInfo("imap-mcp", "0.1.0").immediateExecution(true)
                .validateToolInputs(false).tools(tools).build();

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
        addFilter(ctx, new PostOnlyFilter(), MCP_PATH);
        addFilter(ctx, bearerFilter, MCP_PATH);
        ctx.addServlet(new ServletHolder(transport), MCP_PATH);
        if (healthServlet != null) {
            ctx.addServlet(new ServletHolder(healthServlet), HEALTH_PATH);
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
        if (mcpServer != null) {
            mcpServer.closeGracefully();
        }
    }

    /** The actually bound port (useful with port 0). */
    public int port() {
        return connector.getLocalPort();
    }

    public String host() {
        return connector.getHost();
    }
}
