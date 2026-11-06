package de.alexanderlindhorst.imapmcp.app;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class MainTest {

    private AppProcess run(Map<String, String> env, String... args) throws Exception {
        AppProcess p = AppProcess.start(List.of(), List.of(args), env);
        assertThat(p.waitFor(20)).as("process exits").isTrue();
        return p;
    }

    // AC1

    @Test
    void noArgsPrintsUsageAndExitsTwo() throws Exception {
        try (AppProcess p = run(Map.of())) {
            assertThat(p.exitCode()).isEqualTo(2);
            assertThat(p.stderr()).contains("Usage").contains("--mode");
            assertThat(p.stdoutRest()).isEmpty();
        }
    }

    @Test
    void unknownModeExitsTwo() throws Exception {
        try (AppProcess p = run(Map.of(), "--mode", "bogus")) {
            assertThat(p.exitCode()).isEqualTo(2);
            assertThat(p.stderr()).contains("Usage");
            assertThat(p.stdoutRest()).isEmpty();
        }
    }

    @Test
    void httpPublicDispatchesToItsLauncher() throws Exception {
        // a missing config file is the launcher's startup error, not a dispatch error
        try (AppProcess p = run(Map.of("IMAP_MCP_CONFIG", "/nonexistent/imap-mcp-config.yaml",
                "IMAP_MCP_STATE_DIR", System.getProperty("java.io.tmpdir") + "/imap-mcp-main-test-state"),
                "--mode=http-public")) {
            assertThat(p.exitCode()).isEqualTo(1);
            assertThat(p.stderr()).contains("startup failed").doesNotContain("not available");
            assertThat(p.stdoutRest()).isEmpty();
        }
    }

    @Test
    void modeHttpPublicFromTheEnvironmentDispatches() throws Exception {
        try (AppProcess p = run(Map.of("IMAP_MCP_MODE", "http-public", "IMAP_MCP_CONFIG", "/nonexistent/c.yaml",
                "IMAP_MCP_STATE_DIR", System.getProperty("java.io.tmpdir") + "/imap-mcp-main-test-state"))) {
            assertThat(p.exitCode()).isEqualTo(1);
            assertThat(p.stderr()).contains("startup failed");
        }
    }

    @Test
    void httpLocalDispatchesToItsLauncher() throws Exception {
        // HttpLocalMain exists since #48: a missing config file is its startup error, not a dispatch error.
        try (AppProcess p = run(Map.of("IMAP_MCP_CONFIG", "/nonexistent/imap-mcp-config.yaml",
                "IMAP_MCP_STATE_DIR", System.getProperty("java.io.tmpdir") + "/imap-mcp-main-test-state"),
                "--mode", "http-local")) {
            assertThat(p.exitCode()).isEqualTo(1);
            assertThat(p.stderr()).contains("startup failed").contains("config file not found");
            assertThat(p.stdoutRest()).isEmpty();
        }
    }
}
