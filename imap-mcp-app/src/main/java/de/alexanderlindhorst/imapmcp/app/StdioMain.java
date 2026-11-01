package de.alexanderlindhorst.imapmcp.app;

import de.alexanderlindhorst.imapmcp.app.config.AppConfig;
import de.alexanderlindhorst.imapmcp.app.config.AppPaths;
import de.alexanderlindhorst.imapmcp.app.config.ConfigLoader;
import de.alexanderlindhorst.imapmcp.app.logging.LoggingBootstrap;
import de.alexanderlindhorst.imapmcp.transport.stdio.ProtocolStreams;
import de.alexanderlindhorst.imapmcp.transport.stdio.StdioServer;
import de.alexanderlindhorst.imapmcp.transport.stdio.StdioStreams;
import io.modelcontextprotocol.json.McpJsonDefaults;
import java.nio.file.Path;
import java.time.Clock;
import java.util.Map;

/**
 * Stdio launcher (SPEC 2, 5.6). The order of the steps is the point of this class: capture the protocol streams,
 * set the system properties logging and Reactor read at class-init time, only then touch SLF4J. Hence no static
 * logger here, and startup failures go to stderr (never stdout) as one line.
 */
public final class StdioMain {

    private StdioMain() {
    }

    public static void main(String[] args) {
        int exit = run(args);
        // The SDK's executors are non-daemon; a graceful close already happened, the exit makes it certain.
        System.exit(exit);
    }

    private static int run(String[] args) {
        // 1. stdout becomes stderr for everything but the protocol, before anything can print.
        ProtocolStreams streams = StdioStreams.capture();
        boolean loggingUp = false;
        AppComponents components = null;
        try {
            // 2. paths and the system properties that must precede Reactor and Logback initialisation.
            AppPaths paths = AppPaths.resolve(args, System.getenv(), Path.of(System.getProperty("user.home")),
                    Path.of("").toAbsolutePath());
            System.setProperty(StdioServer.VIRTUAL_THREADS_PROPERTY, "true");
            // 3. the first SLF4J call happens inside init.
            loggingUp = LoggingBootstrap.init(paths.logDir());
            if (!loggingUp) {
                System.err.println("imap-mcp: warning: cannot write logs to " + paths.logDir() + "; logging disabled");
            }
            // 4. config and wiring.
            AppConfig config = ConfigLoader.load(paths.configFile(), System.getenv());
            components = AppComponents.create(config, paths, Clock.systemUTC(), System.getenv());
            // 5. serve until stdin reaches EOF.
            StdioServer server = StdioServer.start(streams, McpJsonDefaults.getMapper(),
                    components.statefulToolSpecs(), AppVersion.serverInfo());
            if (loggingUp) {
                org.slf4j.LoggerFactory.getLogger("imapmcp.app").info("imap-mcp stdio server started");
            }
            server.awaitTermination();
            return 0;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return 1;
        } catch (RuntimeException | LinkageError e) {
            return fail(loggingUp, e);
        } finally {
            // 6. shutdown in reverse order.
            if (components != null) {
                components.close();
            }
            if (loggingUp) {
                LoggingBootstrap.shutdown();
            }
        }
    }

    /** One actionable line on stderr, no stack trace; the log gets the same message. */
    private static int fail(boolean loggingUp, Throwable e) {
        String message = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
        System.err.println("imap-mcp: startup failed: " + message);
        if (loggingUp) {
            org.slf4j.LoggerFactory.getLogger("imapmcp.app").error("Startup failed: {}", message);
        }
        return 1;
    }
}
