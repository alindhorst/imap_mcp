package de.alexanderlindhorst.imapmcp.it;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/**
 * Everything needed to start the server under test: config file, state and log dir, the environment (secrets and
 * directory overrides) and extra JVM arguments (truststore).
 */
public record ServerConfig(Path configFile, Path stateDir, Path logDir, Map<String, String> env,
        List<String> jvmArgs) {

    /** Main log file of the server (SPEC 5.6). */
    public Path logFile() {
        return logDir.resolve("imap-mcp.log");
    }
}
