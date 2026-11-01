package de.alexanderlindhorst.imapmcp.it;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Starts the shaded jar the way a client does (SPEC 5.6):
 * {@code java -Xlog:disable -Xlog:all=warning:stderr [jvm args] -jar imap-mcp.jar --mode <mode> --config <file>}.
 * stdout and stderr are separate pipes; the jar comes from the system property {@code imapmcp.jar}.
 */
public final class JarLauncher {

    private JarLauncher() {
    }

    public static Path jar() {
        String p = System.getProperty("imapmcp.jar");
        if (p == null || p.isBlank()) {
            throw new IllegalStateException("system property imapmcp.jar is not set (run through failsafe)");
        }
        return Path.of(p);
    }

    /** @param mode {@code stdio}, {@code http-local}, or null to start without {@code --mode}. */
    public static RunningServer start(ServerConfig config, String mode, String... extraArgs) throws IOException {
        return new RunningServer(builder(config, mode, extraArgs).start());
    }

    static ProcessBuilder builder(ServerConfig config, String mode, String... extraArgs) {
        List<String> cmd = new ArrayList<>();
        cmd.add(Path.of(System.getProperty("java.home"), "bin", "java").toString());
        cmd.addAll(List.of("-Xlog:disable", "-Xlog:all=warning:stderr"));
        cmd.addAll(config.jvmArgs());
        cmd.addAll(List.of("-jar", jar().toString()));
        if (mode != null) {
            cmd.addAll(List.of("--mode", mode));
        }
        cmd.addAll(List.of("--config", config.configFile().toString()));
        cmd.addAll(List.of(extraArgs));
        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.environment().keySet().removeIf(k -> k.startsWith("IMAP_MCP_") || k.startsWith("XDG_"));
        pb.environment().putAll(config.env());
        return pb;
    }
}
