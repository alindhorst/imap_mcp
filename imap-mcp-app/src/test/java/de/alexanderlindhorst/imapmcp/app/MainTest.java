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
    void httpPublicIsNotAvailable() throws Exception {
        try (AppProcess p = run(Map.of(), "--mode=http-public")) {
            assertThat(p.exitCode()).isEqualTo(2);
            assertThat(p.stderr()).contains("not available in this version");
            assertThat(p.stdoutRest()).isEmpty();
        }
    }

    @Test
    void modeFallsBackToEnvironment() throws Exception {
        try (AppProcess p = run(Map.of("IMAP_MCP_MODE", "http-public"))) {
            assertThat(p.exitCode()).isEqualTo(2);
            assertThat(p.stderr()).contains("not available in this version");
        }
    }

    @Test
    void missingLauncherClassIsAClearErrorWithExitTwo() throws Exception {
        // HttpLocalMain is delivered by #48; until then the dispatcher must fail cleanly.
        try (AppProcess p = run(Map.of(), "--mode", "http-local")) {
            assertThat(p.exitCode()).isEqualTo(2);
            assertThat(p.stderr()).contains("http-local").contains("not available");
            assertThat(p.stdoutRest()).isEmpty();
        }
    }
}
