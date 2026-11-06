package de.alexanderlindhorst.imapmcp.app;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;

/**
 * Jar entry point (SPEC 2, 5.1): picks the launcher for {@code --mode stdio|http-local} (fallback env
 * {@code IMAP_MCP_MODE}) and loads only that class by name, so the stdio path never loads Jetty classes. No logger
 * and no static reference to a launcher: both would defeat that and the stdout/logging start-up order of
 * {@code StdioMain}. Output here goes to stderr only; stdout belongs to the protocol.
 */
public final class Main {

    static final String STDIO_LAUNCHER = "de.alexanderlindhorst.imapmcp.app.StdioMain";
    static final String HTTP_LOCAL_LAUNCHER = "de.alexanderlindhorst.imapmcp.app.HttpLocalMain";
    static final String HTTP_PUBLIC_LAUNCHER = "de.alexanderlindhorst.imapmcp.app.HttpPublicMain";

    private static final String ENV_MODE = "IMAP_MCP_MODE";
    private static final String USAGE = """
            Usage: java -Xlog:disable -Xlog:all=warning:stderr -jar imap-mcp.jar --mode stdio|http-local|http-public [--config <file>]
              --mode     stdio (MCP over stdin/stdout), http-local (loopback HTTP) or http-public (OAuth, multi-tenant); fallback env IMAP_MCP_MODE
              --config   config file (default: env IMAP_MCP_CONFIG, else ~/.config/imap-mcp/config.yaml)""";

    private Main() {
    }

    public static void main(String[] args) {
        String mode = modeArg(args);
        if (mode == null) {
            mode = System.getenv(ENV_MODE);
        }
        if (mode == null || mode.isBlank()) {
            usage("no mode given");
            return;
        }
        switch (mode.strip()) {
            case "stdio" -> launch(STDIO_LAUNCHER, "stdio", args);
            case "http-local" -> launch(HTTP_LOCAL_LAUNCHER, "http-local", args);
            case "http-public" -> launch(HTTP_PUBLIC_LAUNCHER, "http-public", args);
            default -> usage("unknown mode '" + mode.strip() + "'");
        }
    }

    private static void usage(String problem) {
        System.err.println("imap-mcp: " + problem);
        System.err.println(USAGE);
        System.exit(2);
    }

    private static void launch(String className, String mode, String[] args) {
        Method main;
        try {
            main = Class.forName(className).getMethod("main", String[].class);
        } catch (ClassNotFoundException | NoSuchMethodException | LinkageError e) {
            System.err.println("imap-mcp: mode " + mode + " is not available in this build (launcher missing)");
            System.exit(2);
            return;
        }
        try {
            main.invoke(null, (Object) args);
        } catch (InvocationTargetException e) {
            System.err.println("imap-mcp: " + mode + " launcher failed: " + e.getCause());
            System.exit(1);
        } catch (IllegalAccessException e) {
            System.err.println("imap-mcp: mode " + mode + " is not available in this build (launcher inaccessible)");
            System.exit(2);
        }
    }

    /** {@code --mode x} or {@code --mode=x}; null if absent. */
    private static String modeArg(String[] args) {
        for (int i = 0; i < args.length; i++) {
            if (args[i].equals("--mode")) {
                return i + 1 < args.length ? args[i + 1] : "";
            }
            if (args[i].startsWith("--mode=")) {
                return args[i].substring("--mode=".length());
            }
        }
        return null;
    }
}
