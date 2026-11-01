package de.alexanderlindhorst.imapmcp.app.config;

import java.nio.file.Path;
import java.util.Map;
import java.util.Set;

/**
 * Resolved config file, state dir and log dir (SPEC 5.7). Pure function: no filesystem access, no
 * logging, no console output, because it runs before the logging bootstrap.
 */
public record AppPaths(Path configFile, Path stateDir, Path logDir) {

    public static final String ENV_CONFIG = "IMAP_MCP_CONFIG";
    public static final String ENV_STATE_DIR = "IMAP_MCP_STATE_DIR";
    public static final String ENV_LOG_DIR = "IMAP_MCP_LOG_DIR";
    public static final String ENV_XDG_CONFIG_HOME = "XDG_CONFIG_HOME";
    public static final String ENV_XDG_STATE_HOME = "XDG_STATE_HOME";

    /** Every environment variable this class reads (compared against the README by #60). */
    public static final Set<String> ENV_VARS =
            Set.of(ENV_CONFIG, ENV_STATE_DIR, ENV_LOG_DIR, ENV_XDG_CONFIG_HOME, ENV_XDG_STATE_HOME);

    private static final String APP_DIR = "imap-mcp";

    public static AppPaths resolve(String[] args, Map<String, String> env, Path userHome, Path workingDir) {
        Path cwd = workingDir.toAbsolutePath();
        String cli = configArg(args);

        Path config;
        if (cli != null) {
            config = absolute(cli, cwd);
        } else if (isSet(env.get(ENV_CONFIG))) {
            config = absolute(env.get(ENV_CONFIG), cwd);
        } else {
            Path base = xdgBase(env.get(ENV_XDG_CONFIG_HOME), userHome.resolve(".config"));
            config = base.resolve(APP_DIR).resolve("config.yaml");
        }

        Path state;
        if (isSet(env.get(ENV_STATE_DIR))) {
            state = absolute(env.get(ENV_STATE_DIR), cwd);
        } else {
            state = xdgBase(env.get(ENV_XDG_STATE_HOME), userHome.resolve(".local").resolve("state"))
                    .resolve(APP_DIR);
        }

        Path logs = isSet(env.get(ENV_LOG_DIR)) ? absolute(env.get(ENV_LOG_DIR), cwd) : state.resolve("logs");
        return new AppPaths(config, state, logs);
    }

    /** Extracts {@code --config <path>} or {@code --config=<path>}; all other arguments are ignored. */
    private static String configArg(String[] args) {
        for (int i = 0; i < args.length; i++) {
            String a = args[i];
            if (a.equals("--config")) {
                if (i + 1 >= args.length || args[i + 1].isBlank()) {
                    throw new IllegalArgumentException("--config requires a file path, e.g. --config /path/config.yaml");
                }
                return args[i + 1];
            }
            if (a.startsWith("--config=")) {
                String v = a.substring("--config=".length());
                if (v.isBlank()) {
                    throw new IllegalArgumentException("--config requires a file path, e.g. --config=/path/config.yaml");
                }
                return v;
            }
        }
        return null;
    }

    private static boolean isSet(String v) {
        return v != null && !v.isBlank();
    }

    /** XDG base dirs count only when set and absolute; otherwise the default applies. */
    private static Path xdgBase(String xdg, Path fallback) {
        if (isSet(xdg) && Path.of(xdg).isAbsolute()) {
            return Path.of(xdg);
        }
        return fallback;
    }

    private static Path absolute(String value, Path cwd) {
        return cwd.resolve(value).normalize();
    }
}
