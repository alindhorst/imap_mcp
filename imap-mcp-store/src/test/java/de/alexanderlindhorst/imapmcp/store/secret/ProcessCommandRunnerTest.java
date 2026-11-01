package de.alexanderlindhorst.imapmcp.store.secret;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeoutException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

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

    @Test
    void hangingProcessIsDestroyedOnTimeout() {
        long start = System.nanoTime();
        assertThatThrownBy(() -> runner.run(List.of("sleep", "30"), Duration.ofMillis(300)))
                .isInstanceOf(TimeoutException.class);
        assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(Duration.ofSeconds(1).plusMillis(300));
    }

    @Test
    void missingExecutableIsAnIoException() {
        assertThatThrownBy(() -> runner.run(List.of("definitely-not-a-command-59"), Duration.ofSeconds(1)))
                .isInstanceOf(IOException.class);
    }
}
