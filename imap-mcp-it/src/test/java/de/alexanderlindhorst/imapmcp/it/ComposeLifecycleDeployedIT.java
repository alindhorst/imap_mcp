package de.alexanderlindhorst.imapmcp.it;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Lifecycle of {@link DeployedCompose} on a tiny stack: nothing of the project remains after the teardown, on a pass
 * and on a failed start, and a failed start leaves the compose logs (acceptance 4 of #427). Never skipped: a missing
 * engine fails the run (DockerGuard).
 */
class ComposeLifecycleDeployedIT {

    @TempDir
    Path dir;

    private Path stack(boolean healthy) throws Exception {
        Path compose = dir.resolve("compose.yaml");
        Files.writeString(compose, """
                services:
                  svc:
                    image: %s
                    entrypoint: ["sh", "-c", "mkdir -p /tmp/x; echo marker > /tmp/x/m.log; sleep 600"]
                    ports: ["127.0.0.1::3128"]
                    volumes: ["data:/data"]
                    healthcheck:
                      test: ["CMD", "%s"]
                      interval: 1s
                      timeout: 2s
                      retries: 2
                    networks: [n]
                  dep:
                    image: %s
                    entrypoint: ["sleep", "600"]
                    depends_on:
                      svc:
                        condition: service_healthy
                    networks: [n]
                networks:
                  n: {}
                volumes:
                  data: {}
                """.formatted(ItImages.SQUID, healthy ? "true" : "false", ItImages.SQUID));
        return compose;
    }

    private static String project() {
        return "imcp-lc-" + HexFormat.of().formatHex(UUID.randomUUID().toString().getBytes()).substring(0, 8);
    }

    @Test
    void aPassingStackIsRemovedCompletelyByTheTeardown() throws Exception {
        stack(true);
        DeployedCompose compose = new DeployedCompose(project(), dir, List.of("compose.yaml"));
        try {
            compose.start(Duration.ofMinutes(3));
            assertThat(compose.hostPort("svc", 3128)).isPositive();
            assertThat(compose.leftovers()).anyMatch(s -> s.startsWith("container")).anyMatch(s -> s.startsWith("network"))
                    .anyMatch(s -> s.startsWith("volume"));
        } finally {
            compose.stop();
        }
        assertThat(compose.leftovers()).isEmpty();
    }

    @Test
    void aFailedStartFailsLeavesLogsAndLeavesNothingBehind() throws Exception {
        stack(false);
        DeployedCompose compose = new DeployedCompose(project(), dir, List.of("compose.yaml"))
                .collectingFiles("svc", "/tmp/x");
        try {
            assertThatThrownBy(() -> compose.start(Duration.ofMinutes(3))).isInstanceOf(RuntimeException.class);
            assertThat(compose.logsDir()).isDirectory();
            try (Stream<Path> files = Files.list(compose.logsDir())) {
                assertThat(files.toList()).isNotEmpty();
            }
            String containers = Files.readString(compose.logsDir().resolve("containers.txt"));
            assertThat(containers).contains("service=svc").containsPattern("health=(unhealthy|starting)");
            try (Stream<Path> files = Files.walk(compose.logsDir().resolve("svc-files"))) {
                Path marker = files.filter(f -> f.getFileName().toString().equals("m.log")).findFirst().orElseThrow();
                assertThat(Files.readString(marker)).isEqualTo("marker\n");
            }
        } finally {
            compose.stop();
        }
        assertThat(compose.leftovers()).isEmpty();
    }
}
