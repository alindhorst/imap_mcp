package de.alexanderlindhorst.imapmcp.store.secret;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Exercises the real ProcessBuilder runner with harmless POSIX commands. */
class ProcessCommandRunnerTest {

    private final ProcessCommandRunner runner = new ProcessCommandRunner();

    @BeforeEach
    void posixOnly() {
        assumeTrue(Platform.current() != Platform.WINDOWS);
    }

    @Test
    void capturesStdoutExitCodeAndStderrLength() throws Exception {
        var r = runner.run(List.of("sh", "-c", "printf 'hi there'; printf 'err' >&2; exit 3"), Duration.ofSeconds(5));
        assertThat(r.exitCode()).isEqualTo(3);
        assertThat(new String(r.stdout(), StandardCharsets.UTF_8)).isEqualTo("hi there");
        assertThat(r.stderrLength()).isEqualTo(3);
    }

    @Test
    void argumentsAreNotInterpretedByAShell() throws Exception {
        var r = runner.run(List.of("printf", "%s", "$(echo x) \"q\" a b"), Duration.ofSeconds(5));
        assertThat(new String(r.stdout(), StandardCharsets.UTF_8)).isEqualTo("$(echo x) \"q\" a b");
    }

    @Test
    void stdinIsClosed() throws Exception {
        var r = runner.run(List.of("cat"), Duration.ofSeconds(5));
        assertThat(r.exitCode()).isZero();
        assertThat(r.stdout()).isEmpty();
    }

    @Test
    void largeOutputOnBothPipesDoesNotDeadlock() throws Exception {
        var r = runner.run(List.of("sh", "-c", "head -c 300000 /dev/zero >&2; head -c 300000 /dev/zero"), Duration.ofSeconds(10));
        assertThat(r.stdout()).hasSize(300000);
        assertThat(r.stderrLength()).isEqualTo(300000);
    }

    /** #59 AC4 / #143: the child is really gone after the timeout, not merely abandoned. */
    @Test
    void hangingProcessIsDestroyedOnTimeout(@TempDir Path dir) throws Exception {
        Path pidFile = dir.resolve("pid");
        long start = System.nanoTime();
        assertThatThrownBy(() -> runner.run(
                List.of("sh", "-c", "echo $$ > '" + pidFile + "'; exec sleep 30"), Duration.ofSeconds(1)))
                .isInstanceOf(TimeoutException.class);
        assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(Duration.ofSeconds(2).plusMillis(300));
        long pid = Long.parseLong(Files.readString(pidFile).strip());
        ProcessHandle.of(pid).ifPresent(h -> {
            try {
                h.onExit().get(5, TimeUnit.SECONDS);
            } catch (Exception e) {
                throw new AssertionError("process " + pid + " still alive after the timeout", e);
            }
        });
        assertThat(ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false)).isFalse();
    }

    @Test
    void missingExecutableIsAnIoException() {
        assertThatThrownBy(() -> runner.run(List.of("definitely-not-a-command-59"), Duration.ofSeconds(1)))
                .isInstanceOf(IOException.class);
    }
}
