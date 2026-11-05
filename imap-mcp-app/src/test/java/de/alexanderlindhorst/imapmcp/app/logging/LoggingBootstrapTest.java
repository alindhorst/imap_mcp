package de.alexanderlindhorst.imapmcp.app.logging;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Every test runs the probe in a forked JVM so that stdout can be measured exactly. */
class LoggingBootstrapTest {

    @TempDir
    Path tmp;

    private record Run(int exit, byte[] stdout, String stderr) {
    }

    private Run fork(String scenario, Path logDir, Map<String, String> env, List<String> jvmArgs) throws Exception {
        String java = ProcessHandle.current().info().command().orElseThrow();
        List<String> cmd = new ArrayList<>(List.of(java, "-cp", System.getProperty("java.class.path")));
        cmd.addAll(jvmArgs);
        cmd.addAll(List.of(LoggingProbe.class.getName(), scenario, logDir.toString()));
        Path out = Files.createTempFile(tmp, "out", ".txt");
        Path err = Files.createTempFile(tmp, "err", ".txt");
        ProcessBuilder pb = new ProcessBuilder(cmd).redirectOutput(out.toFile()).redirectError(err.toFile());
        pb.environment().remove("IMAP_MCP_LOG_LEVEL");
        pb.environment().remove("IMAP_MCP_LOG_DIR");
        pb.environment().putAll(env);
        Process p = pb.start();
        assertThat(p.waitFor(60, TimeUnit.SECONDS)).isTrue();
        return new Run(p.exitValue(), Files.readAllBytes(out), Files.readString(err));
    }

    private static String logText(Path dir) throws IOException {
        StringBuilder sb = new StringBuilder();
        try (Stream<Path> files = Files.list(dir)) {
            for (Path f : files.sorted().toList()) {
                sb.append(Files.readString(f));
            }
        }
        return sb.toString();
    }

    private Path fullRun(Map<String, String> env) throws Exception {
        Path dir = tmp.resolve("logs");
        Run r = fork("full", dir, env, List.of());
        assertThat(r.exit()).as(r.stderr()).isZero();
        assertThat(r.stdout()).isEmpty();
        return dir;
    }

    @Test
    void allLevelsJulAndUncaughtExceptionLeaveStdoutEmptyAndLandInFile() throws Exception {
        String log = logText(fullRun(Map.of("IMAP_MCP_LOG_LEVEL", "TRACE")));
        assertThat(log).contains("L-TRACE", "L-DEBUG", "L-INFO", "L-WARN", "L-ERROR", "JUL-INFO", "UNCAUGHT-MARK");
    }

    @Test
    void protocolTraceLoggersAreCappedEvenWithRootTrace() throws Exception {
        String log = logText(fullRun(Map.of("IMAP_MCP_LOG_LEVEL", "TRACE")));
        // every capped logger family of logback.xml: angus (JUL), jakarta.mail (JUL), MCP SDK and Jetty (SLF4J)
        assertThat(log).contains("L-TRACE").doesNotContain("ANGUS-FINEST", "ANGUS-FINE", "MCP-DEBUG",
                "JAKARTA-FINE", "JETTY-DEBUG");
    }

    @Test
    void brokenConfigPrintsNothingOnStdout() throws Exception {
        Path broken = tmp.resolve("broken.xml");
        Files.writeString(broken, "<configuration><appender name=\"X\" class=\"does.not.Exist\"/>"
                + "<root level=\"INFO\"><appender-ref ref=\"X\"/></root></configuration>");
        Run r = fork("broken", tmp.resolve("logs"), Map.of(), List.of("-Dlogback.configurationFile=" + broken));
        assertThat(r.exit()).as(r.stderr()).isZero();
        assertThat(r.stderr()).contains("status-errors=").doesNotContain("status-errors=0");
        assertThat(r.stdout()).isEmpty();
    }

    @Test
    void mdcValuesAppearInFileLine() throws Exception {
        String log = logText(fullRun(Map.of()));
        assertThat(log.lines().filter(l -> l.contains("MDC-MARK")).findFirst().orElseThrow())
                .contains("REQ-1", "TENANT-1", "TOOL-1", "ACCT-1");
    }

    @Test
    void logFileRotatesBySize() throws Exception {
        Path dir = tmp.resolve("logs");
        Run r = fork("rotate", dir, Map.of(), List.of("-Dimapmcp.log.maxFileSize=2KB"));
        assertThat(r.exit()).as(r.stderr()).isZero();
        assertThat(r.stdout()).isEmpty();
        // one rollover: the active file plus one archive that was rolled because it reached the size limit
        try (Stream<Path> files = Files.list(dir)) {
            List<Path> all = files.toList();
            assertThat(all).extracting(p -> p.getFileName().toString()).as(r.stderr())
                    .contains("imap-mcp.log").hasSize(2);
            Path archive = all.stream().filter(p -> !p.getFileName().toString().equals("imap-mcp.log")).findFirst()
                    .orElseThrow();
            assertThat(archive.getFileName().toString()).matches("imap-mcp\\.\\d{4}-\\d{2}-\\d{2}\\.0\\.log");
            assertThat(Files.size(archive)).isGreaterThanOrEqualTo(2048);
        }
    }

    @Test
    void traceLoggerIsDebugWhileRootIsInfo() throws Exception {
        Path dir = tmp.resolve("logs");
        Run r = fork("levels", dir, Map.of(), List.of());
        assertThat(r.exit()).as(r.stderr()).isZero();
        String log = logText(dir);
        assertThat(log).contains("TRACE-LOGGER-DEBUG").doesNotContain("OTHER-DEBUG");
    }

    /** SPEC 5.6: the purge audit line is written at INFO even when the root level is higher. */
    @Test
    void auditLoggerStaysInfoWhileRootIsWarn() throws Exception {
        Path dir = tmp.resolve("logs");
        Run r = fork("audit", dir, Map.of("IMAP_MCP_LOG_LEVEL", "WARN"), List.of());
        assertThat(r.exit()).as(r.stderr()).isZero();
        assertThat(logText(dir)).contains("AUDIT-INFO").doesNotContain("OTHER-INFO");
    }

    @Test
    void invalidLevelFallsBackToInfo() throws Exception {
        Path dir = tmp.resolve("logs");
        Run r = fork("full", dir, Map.of("IMAP_MCP_LOG_LEVEL", "chatty"), List.of());
        assertThat(r.exit()).as(r.stderr()).isZero();
        assertThat(logText(dir)).contains("L-INFO").doesNotContain("L-DEBUG");
    }

    @Test
    void lowerCaseLevelIsAccepted() throws Exception {
        Path dir = tmp.resolve("logs");
        fork("full", dir, Map.of("IMAP_MCP_LOG_LEVEL", "debug"), List.of());
        assertThat(logText(dir)).contains("L-DEBUG").doesNotContain("L-TRACE");
    }

    @Test
    void missingLogDirIsCreatedOwnerOnly() throws Exception {
        Path dir = tmp.resolve("a").resolve("b");
        fork("full", dir, Map.of(), List.of());
        assertThat(dir).isDirectory();
        assertThat(Files.getPosixFilePermissions(dir)).containsExactlyInAnyOrder(
                java.nio.file.attribute.PosixFilePermission.OWNER_READ,
                java.nio.file.attribute.PosixFilePermission.OWNER_WRITE,
                java.nio.file.attribute.PosixFilePermission.OWNER_EXECUTE);
    }

    @Test
    void uncreatableLogDirDisablesLoggingSilently() throws Exception {
        Path blocker = tmp.resolve("file");
        Files.writeString(blocker, "x");
        Run r = fork("unwritable", blocker.resolve("logs"), Map.of(), List.of());
        assertThat(r.exit()).as(r.stderr()).isZero();
        assertThat(r.stdout()).isEmpty();
        assertThat(r.stderr()).contains("active=false");
        assertThat(new File(blocker.toString())).isFile();
    }
}
