package de.alexanderlindhorst.imapmcp.app;

import de.alexanderlindhorst.imapmcp.app.config.AppPaths;
import de.alexanderlindhorst.imapmcp.app.config.PublicConfig;
import de.alexanderlindhorst.imapmcp.app.config.PublicConfigLoader;
import de.alexanderlindhorst.imapmcp.app.logging.LoggingBootstrap;
import de.alexanderlindhorst.imapmcp.transport.http.HttpLocalServer;
import de.alexanderlindhorst.imapmcp.transport.http.IssuerJwks;
import java.nio.file.Path;
import java.time.Clock;
import java.util.concurrent.CountDownLatch;

/**
 * http-public launcher (SPEC 2, 5.3, 5.7): multi-tenant Streamable HTTP behind a TLS reverse proxy, OAuth 2.1
 * resource server, Postgres. Loaded by {@link Main} by name only. Same start-up discipline as the other launchers:
 * system properties and logging first; every startup failure is one stderr line and exit 1 (naming the config path,
 * never a secret); nothing is ever written to stdout.
 */
public final class HttpPublicMain {

    private static final String VIRTUAL_THREADS_PROPERTY = "reactor.schedulers.defaultBoundedElasticOnVirtualThreads";

    private HttpPublicMain() {
    }

    public static void main(String[] args) {
        System.exit(run(args));
    }

    private static int run(String[] args) {
        boolean loggingUp = false;
        PublicComponents components = null;
        try {
            AppPaths paths = AppPaths.resolve(args, System.getenv(), Path.of(System.getProperty("user.home")),
                    Path.of("").toAbsolutePath());
            System.setProperty(VIRTUAL_THREADS_PROPERTY, "true");
            loggingUp = LoggingBootstrap.init(paths.logDir());
            if (!loggingUp) {
                System.err.println("imap-mcp: warning: cannot write logs to " + paths.logDir() + "; logging disabled");
            }
            PublicConfig config = PublicConfigLoader.load(paths.configFile(), System.getenv());
            components = PublicComponents.create(config, paths, Clock.systemUTC(), System.getenv());
            // the issuer's keys are discovered lazily on the first token (an unreachable identity provider does not
            // stop the service from starting); the issuer must be https (IssuerJwks.create)
            HttpLocalServer server = HttpPublicServer.build(config, components,
                    IssuerJwks.create(config.oauth().issuer(), components.metrics()));
            server.start();
            org.slf4j.LoggerFactory.getLogger("imapmcp.app")
                    .info("imap-mcp http-public listening on {}:{}", server.host(), server.port());

            PublicComponents running = components;
            boolean logging = loggingUp;
            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                HttpLocalMain.shutdown(server::beginShutdown, server::stop, running::close,
                        logging ? LoggingBootstrap::shutdown : () -> { });
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
    private static int fail(boolean loggingUp, PublicComponents components, Throwable e) {
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
}
