package de.alexanderlindhorst.imapmcp.transport.stdio;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Runs the probe in a forked JVM so the real stdout/stderr/stdin of a process can be observed. */
class StdioStreamsTest {

    @TempDir
    Path tmp;

    private String stdout;
    private String stderr;

    private void runProbe(String stdin) throws Exception {
        String java = ProcessHandle.current().info().command().orElseThrow();
        File out = tmp.resolve("out").toFile();
        File err = tmp.resolve("err").toFile();
        Process p = new ProcessBuilder(List.of(java, "-cp", System.getProperty("java.class.path"),
                StdioStreamsProbe.class.getName()))
                .redirectOutput(out).redirectError(err).start();
        p.getOutputStream().write(stdin.getBytes());
        p.getOutputStream().close();
        assertThat(p.waitFor(60, TimeUnit.SECONDS)).isTrue();
        stdout = Files.readString(out.toPath());
        stderr = Files.readString(err.toPath());
        assertThat(p.exitValue()).as(stderr).isZero();
    }

    @Test
    void systemOutGoesToStderrAndProtocolStreamToStdout() throws Exception {
        runProbe("hello");
        assertThat(stdout).isEqualTo("PROTOCOL");
        assertThat(stderr).contains("via-system-out");
    }

    @Test
    void systemInIsEofAndProtocolInReceivesProcessStdin() throws Exception {
        runProbe("hello");
        assertThat(stderr).contains("system-in=-1").contains("protocol-in=hello");
    }

    @Test
    void secondCaptureReturnsSameInstance() throws Exception {
        runProbe("");
        assertThat(stderr).contains("same=true");
    }
}
