package de.alexanderlindhorst.imapmcp.app.logging;

import ch.qos.logback.classic.LoggerContext;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Locale;
import java.util.Set;
import org.slf4j.ILoggerFactory;
import org.slf4j.LoggerFactory;
import org.slf4j.bridge.SLF4JBridgeHandler;

/**
 * Logging bootstrap (SPEC 5.6). Logs go to a rolling file only; nothing is ever written to stdout or
 * stderr by logging, including Logback's own status messages.
 *
 * <p>Must run after the stdio streams are captured (#38) and before the first SLF4J call; this class
 * deliberately holds no logger fields for that reason.
 */
public final class LoggingBootstrap {

    public static final String ENV_LOG_LEVEL = "IMAP_MCP_LOG_LEVEL";
    static final String PROP_LOG_DIR = "IMAP_MCP_LOG_DIR";
    static final String PROP_LOG_LEVEL = "IMAP_MCP_LOG_LEVEL";
    private static final Set<String> LEVELS = Set.of("TRACE", "DEBUG", "INFO", "WARN", "ERROR");
    private static final String DEFAULT_LEVEL = "INFO";

    private LoggingBootstrap() {
    }

    /** Initializes logging with the root level from {@code IMAP_MCP_LOG_LEVEL} (default INFO). */
    public static boolean init(Path logDir) {
        return init(logDir, System.getenv(ENV_LOG_LEVEL));
    }

    /**
     * Initializes logging. Anything but TRACE/DEBUG/INFO/WARN/ERROR (case-insensitive) means INFO.
     *
     * @return true if file logging is active. If the log directory cannot be created, nothing is
     *         printed; all appenders are detached and logging is silently disabled, and false is
     *         returned so the caller may report it by other means (not stdout).
     */
    public static boolean init(Path logDir, String level) {
        // Must precede the first LoggerFactory call: Logback reads these while configuring itself.
        System.setProperty("logback.statusListenerClass", "ch.qos.logback.core.status.NopStatusListener");
        System.setProperty(PROP_LOG_DIR, logDir.toAbsolutePath().toString());
        System.setProperty(PROP_LOG_LEVEL, sanitizeLevel(level));

        boolean dirOk = createLogDir(logDir);

        SLF4JBridgeHandler.removeHandlersForRootLogger();
        SLF4JBridgeHandler.install();

        if (!dirOk && LoggerFactory.getILoggerFactory() instanceof LoggerContext ctx) {
            ctx.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME).detachAndStopAllAppenders();
        }

        Thread.setDefaultUncaughtExceptionHandler((thread, t) ->
                LoggerFactory.getLogger("imapmcp.uncaught")
                        .error("Uncaught exception in thread {}", thread.getName(), t));
        return dirOk;
    }

    /** Stops the Logback context, flushing and closing the file appender. */
    public static void shutdown() {
        SLF4JBridgeHandler.uninstall();
        ILoggerFactory factory = LoggerFactory.getILoggerFactory();
        if (factory instanceof LoggerContext ctx) {
            ctx.stop();
        }
    }

    static String sanitizeLevel(String level) {
        if (level == null) {
            return DEFAULT_LEVEL;
        }
        String upper = level.strip().toUpperCase(Locale.ROOT);
        return LEVELS.contains(upper) ? upper : DEFAULT_LEVEL;
    }

    private static boolean createLogDir(Path dir) {
        try {
            if (Files.isDirectory(dir)) {
                return true;
            }
            try {
                Files.createDirectories(dir, PosixFilePermissions.asFileAttribute(
                        PosixFilePermissions.fromString("rwx------")));
            } catch (UnsupportedOperationException e) {
                Files.createDirectories(dir); // non-POSIX file system
            }
            return Files.isWritable(dir);
        } catch (IOException | SecurityException e) {
            return false;
        }
    }
}
