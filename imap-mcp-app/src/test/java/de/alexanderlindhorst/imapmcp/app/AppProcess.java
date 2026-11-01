package de.alexanderlindhorst.imapmcp.app;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/** Launches {@code Main} in a child JVM on the test runtime classpath, exactly as a client would (SPEC 5.6). */
final class AppProcess implements AutoCloseable {

    private final Process process;
    private final ByteArrayOutputStream err = new ByteArrayOutputStream();
    private final Thread errPump;

    private AppProcess(Process process) {
        this.process = process;
        this.errPump = Thread.ofVirtual().start(() -> {
            try {
                process.getErrorStream().transferTo(err);
            } catch (IOException ignored) {
                // process gone
            }
        });
    }

    /** @param jvmArgs extra JVM arguments (system properties) placed before the main class. */
    static AppProcess start(List<String> jvmArgs, List<String> args, Map<String, String> env) throws IOException {
        List<String> cmd = new ArrayList<>();
        cmd.add(Path.of(System.getProperty("java.home"), "bin", "java").toString());
        cmd.addAll(List.of("-Xlog:disable", "-Xlog:all=warning:stderr"));
        cmd.addAll(jvmArgs);
        cmd.addAll(List.of("-cp", classpath(), "de.alexanderlindhorst.imapmcp.app.Main"));
        cmd.addAll(args);
        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.environment().keySet().removeIf(k -> k.startsWith("IMAP_MCP_") || k.startsWith("XDG_"));
        pb.environment().putAll(env);
        return new AppProcess(pb.start());
    }

    private static String classpath() {
        String cp = System.getProperty("surefire.test.class.path");
        return cp != null ? cp : System.getProperty("java.class.path");
    }

    Process process() {
        return process;
    }

    boolean waitFor(long seconds) throws InterruptedException {
        return process.waitFor(seconds, TimeUnit.SECONDS);
    }

    int exitCode() {
        return process.exitValue();
    }

    byte[] stdoutRest() throws IOException {
        return process.getInputStream().readAllBytes();
    }

    String stderr() throws InterruptedException {
        errPump.join(2000);
        return err.toString(java.nio.charset.StandardCharsets.UTF_8);
    }

    @Override
    public void close() {
        process.destroyForcibly();
    }
}
