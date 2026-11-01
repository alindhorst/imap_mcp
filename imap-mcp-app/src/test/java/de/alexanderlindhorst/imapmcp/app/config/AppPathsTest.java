package de.alexanderlindhorst.imapmcp.app.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;

class AppPathsTest {

    private static final Path HOME = Path.of("/home/u");
    private static final Path CWD = Path.of("/work");
    private static final String[] NO_ARGS = {};

    private static AppPaths resolve(String[] args, Map<String, String> env) {
        return AppPaths.resolve(args, env, HOME, CWD);
    }

    @Test
    void cliBeatsEnvBeatsXdgForConfig() {
        Map<String, String> env = Map.of("IMAP_MCP_CONFIG", "/b.yaml", "XDG_CONFIG_HOME", "/x");
        assertThat(resolve(new String[]{"--config", "/a.yaml"}, env).configFile()).isEqualTo(Path.of("/a.yaml"));
        assertThat(resolve(new String[]{"--config=/a.yaml"}, env).configFile()).isEqualTo(Path.of("/a.yaml"));
        assertThat(resolve(NO_ARGS, env).configFile()).isEqualTo(Path.of("/b.yaml"));
    }

    @Test
    void configXdgAndHomeDefaults() {
        assertThat(resolve(NO_ARGS, Map.of("XDG_CONFIG_HOME", "/x")).configFile())
                .isEqualTo(Path.of("/x/imap-mcp/config.yaml"));
        Path home = Path.of("/home/u/.config/imap-mcp/config.yaml");
        assertThat(resolve(NO_ARGS, Map.of()).configFile()).isEqualTo(home);
        assertThat(resolve(NO_ARGS, Map.of("XDG_CONFIG_HOME", "rel")).configFile()).isEqualTo(home);
    }

    @Test
    void stateDirPrecedence() {
        assertThat(resolve(NO_ARGS, Map.of()).stateDir()).isEqualTo(Path.of("/home/u/.local/state/imap-mcp"));
        assertThat(resolve(NO_ARGS, Map.of("XDG_STATE_HOME", "/s")).stateDir()).isEqualTo(Path.of("/s/imap-mcp"));
        assertThat(resolve(NO_ARGS, Map.of("XDG_STATE_HOME", "rel")).stateDir())
                .isEqualTo(Path.of("/home/u/.local/state/imap-mcp"));
        assertThat(resolve(NO_ARGS, Map.of("XDG_STATE_HOME", "/s", "IMAP_MCP_STATE_DIR", "/st")).stateDir())
                .isEqualTo(Path.of("/st"));
    }

    @Test
    void logDirDefaultsToStateLogsAndCanBeOverridden() {
        assertThat(resolve(NO_ARGS, Map.of()).logDir()).isEqualTo(Path.of("/home/u/.local/state/imap-mcp/logs"));
        assertThat(resolve(NO_ARGS, Map.of("IMAP_MCP_STATE_DIR", "/st")).logDir()).isEqualTo(Path.of("/st/logs"));
        assertThat(resolve(NO_ARGS, Map.of("IMAP_MCP_LOG_DIR", "/l")).logDir()).isEqualTo(Path.of("/l"));
    }

    @Test
    void dockerEnvTriple() {
        AppPaths p = resolve(NO_ARGS, Map.of("IMAP_MCP_CONFIG", "/config/config.yaml",
                "IMAP_MCP_STATE_DIR", "/config/state", "IMAP_MCP_LOG_DIR", "/logs"));
        assertThat(p.configFile()).isEqualTo(Path.of("/config/config.yaml"));
        assertThat(p.stateDir()).isEqualTo(Path.of("/config/state"));
        assertThat(p.logDir()).isEqualTo(Path.of("/logs"));
    }

    @Test
    void relativePathsResolveAgainstWorkingDirAndAreAbsolute() {
        AppPaths p = resolve(new String[]{"--config", "conf.yaml"},
                Map.of("IMAP_MCP_STATE_DIR", "state", "IMAP_MCP_LOG_DIR", "../logs"));
        assertThat(p.configFile()).isEqualTo(Path.of("/work/conf.yaml"));
        assertThat(p.stateDir()).isEqualTo(Path.of("/work/state"));
        assertThat(p.logDir()).isEqualTo(Path.of("/logs"));
        assertThat(resolve(NO_ARGS, Map.of()).configFile().isAbsolute()).isTrue();
    }

    @Test
    void otherArgsIgnoredAndMissingValueRejected() {
        assertThat(resolve(new String[]{"--mode", "stdio", "--config", "/a.yaml"}, Map.of()).configFile())
                .isEqualTo(Path.of("/a.yaml"));
        assertThatThrownBy(() -> resolve(new String[]{"--config"}, Map.of()))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("--config");
        assertThatThrownBy(() -> resolve(new String[]{"--config="}, Map.of()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void exposesEnvVariablesRead() {
        assertThat(AppPaths.ENV_VARS).containsExactlyInAnyOrder("IMAP_MCP_CONFIG", "IMAP_MCP_STATE_DIR",
                "IMAP_MCP_LOG_DIR", "XDG_CONFIG_HOME", "XDG_STATE_HOME");
    }

    @Test
    void writesNothingToStdoutOrStderr() {
        PrintStream out = System.out;
        PrintStream err = System.err;
        ByteArrayOutputStream bo = new ByteArrayOutputStream();
        ByteArrayOutputStream be = new ByteArrayOutputStream();
        try {
            System.setOut(new PrintStream(bo));
            System.setErr(new PrintStream(be));
            resolve(new String[]{"--config", "c.yaml"}, Map.of("XDG_STATE_HOME", "/s"));
        } finally {
            System.setOut(out);
            System.setErr(err);
        }
        assertThat(bo.size()).isZero();
        assertThat(be.size()).isZero();
    }
}
