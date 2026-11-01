package de.alexanderlindhorst.imapmcp.it;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;

/** A started server process. stderr is always collected; stdout is the raw stream (the protocol in stdio mode). */
public final class RunningServer implements AutoCloseable {

    private final Process process;
    private final ByteArrayOutputStream err = new ByteArrayOutputStream();
    private final Thread errPump;

    RunningServer(Process process) {
        this.process = process;
        this.errPump = Thread.ofVirtual().start(() -> {
            try {
                process.getErrorStream().transferTo(err);
            } catch (IOException ignored) {
                // process gone
            }
        });
    }

    /** Writes one newline-terminated line to stdin (newline-delimited JSON-RPC). */
    public void send(String line) throws IOException {
        process.getOutputStream().write((line + "\n").getBytes(StandardCharsets.UTF_8));
        process.getOutputStream().flush();
    }

    public void closeStdin() throws IOException {
        process.getOutputStream().close();
    }

    public InputStream stdout() {
        return process.getInputStream();
    }

    public String stderr() throws InterruptedException {
        errPump.join(2000);
        return err.toString(StandardCharsets.UTF_8);
    }

    public boolean waitFor(long seconds) throws InterruptedException {
        return process.waitFor(seconds, TimeUnit.SECONDS);
    }

    public int exitCode() {
        return process.exitValue();
    }

    public Process process() {
        return process;
    }

    @Override
    public void close() {
        process.destroy();
        try {
            if (!process.waitFor(10, TimeUnit.SECONDS)) {
                process.destroyForcibly();
            }
        } catch (InterruptedException e) {
            process.destroyForcibly();
            Thread.currentThread().interrupt();
        }
    }
}
