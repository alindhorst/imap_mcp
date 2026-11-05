package de.alexanderlindhorst.imapmcp.app;

import de.alexanderlindhorst.imapmcp.app.config.AppConfig;
import de.alexanderlindhorst.imapmcp.app.config.AppPaths;
import de.alexanderlindhorst.imapmcp.app.config.ConfigLoader;
import de.alexanderlindhorst.imapmcp.app.logging.LoggingBootstrap;
import de.alexanderlindhorst.imapmcp.transport.http.BearerTokenFilter;
import de.alexanderlindhorst.imapmcp.transport.http.HealthServlet;
import de.alexanderlindhorst.imapmcp.transport.http.HostFilter;
import de.alexanderlindhorst.imapmcp.transport.http.HttpLocalServer;
import de.alexanderlindhorst.imapmcp.transport.http.OriginFilter;
import java.nio.file.Path;
import java.time.Clock;
import java.util.concurrent.CountDownLatch;

/**
 * http-local launcher (SPEC 2, 5.3): loopback Streamable HTTP with a static bearer token and the fixed local
 * tenant. The only app class that may depend on the HTTP transport (loaded by {@link Main} by name). Same start-up
 * discipline as {@code StdioMain}: system properties and logging before any other library; startup failures are one
 * stderr line and exit 1; nothing is ever written to stdout.
 */
public final class HttpLocalMain {

    private static final String VIRTUAL_THREADS_PROPERTY = "reactor.schedulers.defaultBoundedElasticOnVirtualThreads";

    /** A shutdown step; failures are logged and never skip the following steps. */
    @FunctionalInterface
    interface Step {
        void run() throws Exception;
    }

    private HttpLocalMain() {
    }

    public static void main(String[] args) {
        System.exit(run(args));
    }

    private static int run(String[] args) {
        boolean loggingUp = false;
        AppComponents components = null;
        try {
            AppPaths paths = AppPaths.resolve(args, System.getenv(), Path.of(System.getProperty("user.home")),
                    Path.of("").toAbsolutePath());
            System.setProperty(VIRTUAL_THREADS_PROPERTY, "true");
            loggingUp = LoggingBootstrap.init(paths.logDir());
            if (!loggingUp) {
                System.err.println("imap-mcp: warning: cannot write logs to " + paths.logDir() + "; logging disabled");
            }
            AppConfig config = ConfigLoader.load(paths.configFile(), System.getenv());
            components = AppComponents.create(config, paths, Clock.systemUTC(), System.getenv(),
                    AppComponents.Mode.HTTP_LOCAL);
            AppConfig.Http h = config.http();

            // The token comes from http.token or the state dir (generated on first start); never logged here.
            char[] token = components.httpToken();
            HealthServlet health = new HealthServlet();
            HttpLocalServer.Builder builder = HttpLocalServer.builder()
                    .maxRequestBytes((int) Math.min(h.maxRequestBytes(), Integer.MAX_VALUE))
                    .serverInfo(AppVersion.SERVER_NAME, AppVersion.version())
                    .bind(h.bind()).port(h.port()).allowNonLoopbackBind(h.allowNonLoopbackBind())
                    .hostFilter(new HostFilter(h.allowedHosts()))
                    .originFilter(new OriginFilter(h.allowedOrigins()))
                    .bearerFilter(new BearerTokenFilter(token, components.accountContext()))
                    .contextExtractor(BearerTokenFilter.contextExtractor())
                    .healthServlet(health);
            components.statelessToolSpecs(BearerTokenFilter.accountResolver()).forEach(builder::tool);
            HttpLocalServer server = builder.build();
            server.start();
            org.slf4j.LoggerFactory.getLogger("imapmcp.app")
                    .info("imap-mcp http-local listening on {}:{}", server.host(), server.port());

            // From here on the JVM shutdown (SIGTERM) path owns the cleanup; it also decides the exit status.
            AppComponents running = components;
            boolean logging = loggingUp;
            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                shutdown(server::beginShutdown, server::stop, running::close,
                        logging ? LoggingBootstrap::shutdown : () -> { });
                // A signal-driven exit would report 128+15; a clean, completed shutdown is a success.
                Runtime.getRuntime().halt(0);
            }, "imap-mcp-shutdown"));
            new CountDownLatch(1).await();
            return 0;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return 1;
        } catch (Exception | LinkageError e) {
            return fail(loggingUp, components, e);
        }
    }

    /** One actionable line on stderr, no stack trace; the log gets the same message. */
    private static int fail(boolean loggingUp, AppComponents components, Throwable e) {
        String message = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
        System.err.println("imap-mcp: startup failed: " + message);
        if (loggingUp) {
            org.slf4j.LoggerFactory.getLogger("imapmcp.app").error("Startup failed: {}", message);
        }
        if (components != null) {
            components.close();
        }
        if (loggingUp) {
            LoggingBootstrap.shutdown();
        }
        return 1;
    }

    /**
     * Ordered shutdown (SPEC 5.6): /health flips to 503 first so orchestrators see it, then Jetty drains, then the
     * IMAP pool closes, then logging is flushed.
     */
    static void shutdown(Step beginShutdown, Step stop, Step closeComponents, Step closeLogging) {
        for (Step step : new Step[] {beginShutdown, stop, closeComponents}) {
            try {
                step.run();
            } catch (Exception e) {
                System.err.println("imap-mcp: shutdown step failed: " + e.getClass().getSimpleName());
            }
        }
        try {
            closeLogging.run();
        } catch (Exception e) {
            System.err.println("imap-mcp: shutdown step failed: " + e.getClass().getSimpleName());
        }
    }
}
