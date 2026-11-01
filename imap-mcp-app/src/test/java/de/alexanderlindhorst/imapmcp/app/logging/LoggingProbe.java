package de.alexanderlindhorst.imapmcp.app.logging;

import java.nio.file.Path;
import java.util.logging.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

/** Main class for the forked JVMs of {@link LoggingBootstrapTest}; never prints to stdout. */
public final class LoggingProbe {

    private LoggingProbe() {
    }

    public static void main(String[] args) throws Exception {
        String scenario = args[0];
        boolean active = LoggingBootstrap.init(Path.of(args[1]));
        switch (scenario) {
            case "full" -> full();
            case "levels" -> levels();
            case "rotate" -> rotate(Path.of(args[1]));
            case "broken" -> {
                full();
                System.err.println("status-errors=" + statusErrors());
            }
            case "unwritable" -> {
                full();
                System.err.println("active=" + active);
            }
            default -> throw new IllegalArgumentException(scenario);
        }
        LoggingBootstrap.shutdown();
    }

    private static void full() throws Exception {
        var log = LoggerFactory.getLogger("imapmcp.probe");
        log.trace("L-TRACE");
        log.debug("L-DEBUG");
        log.info("L-INFO");
        log.warn("L-WARN");
        log.error("L-ERROR");
        Logger.getLogger("probe.jul").info("JUL-INFO");
        Logger.getLogger("org.eclipse.angus.mail.imap.protocol").finest("ANGUS-FINEST");
        Logger.getLogger("org.eclipse.angus.mail.imap.protocol").fine("ANGUS-FINE");
        LoggerFactory.getLogger("io.modelcontextprotocol.server").debug("MCP-DEBUG");
        MDC.put("req", "REQ-1");
        MDC.put("tenant", "TENANT-1");
        MDC.put("tool", "TOOL-1");
        MDC.put("acct", "ACCT-1");
        log.info("MDC-MARK");
        MDC.clear();
        Thread t = new Thread(() -> {
            throw new IllegalStateException("UNCAUGHT-MARK");
        });
        t.start();
        t.join();
    }

    private static void levels() {
        LoggerFactory.getLogger("imapmcp.imap.trace").debug("TRACE-LOGGER-DEBUG");
        LoggerFactory.getLogger("imapmcp.other").debug("OTHER-DEBUG");
    }

    /**
     * Logback checks the file size only every few invocations or at most once per interval (internal
     * invocation gate, not configurable), so log in batches until three files exist, bounded by a timeout.
     */
    private static void rotate(Path dir) throws Exception {
        var log = LoggerFactory.getLogger("imapmcp.probe");
        long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(20);
        int n = 0;
        while (System.nanoTime() < deadline) {
            for (int i = 0; i < 300; i++) {
                log.info("ROTATE-LINE-{} xxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx", n++);
            }
            try (var files = java.nio.file.Files.list(dir)) {
                if (files.count() > 2) {
                    return;
                }
            }
            Thread.sleep(100);
        }
    }

    private static int statusErrors() {
        var ctx = (ch.qos.logback.classic.LoggerContext) LoggerFactory.getILoggerFactory();
        return (int) ctx.getStatusManager().getCopyOfStatusList().stream()
                .filter(s -> s.getLevel() >= ch.qos.logback.core.status.Status.ERROR).count();
    }
}
