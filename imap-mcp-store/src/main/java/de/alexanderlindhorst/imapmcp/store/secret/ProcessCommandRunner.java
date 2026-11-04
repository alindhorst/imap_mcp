package de.alexanderlindhorst.imapmcp.store.secret;

import java.io.IOException;
import java.io.InputStream;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Default {@link CommandRunner} backed by {@link ProcessBuilder}: no shell, stdin from the null device, environment
 * inherited (secret-tool needs DBUS_SESSION_BUS_ADDRESS). stdout and stderr are drained concurrently on virtual
 * threads so a full pipe cannot block the child.
 */
public final class ProcessCommandRunner implements CommandRunner {

    @Override
    public Result run(List<String> argv, Duration timeout) throws IOException, TimeoutException {
        ProcessBuilder pb = new ProcessBuilder(argv);
        pb.redirectInput(ProcessBuilder.Redirect.from(nullDevice()));
        Process process = pb.start();
        CompletableFuture<byte[]> out = drain(process.getInputStream());
        CompletableFuture<byte[]> err = drain(process.getErrorStream());
        try {
            if (!process.waitFor(timeout.toNanos(), TimeUnit.NANOSECONDS)) {
                process.destroyForcibly();
                out.cancel(true);
                err.cancel(true);
                throw new TimeoutException("command timed out");
            }
            byte[] stdout = out.get(timeout.toNanos(), TimeUnit.NANOSECONDS);
            byte[] stderr = err.get(timeout.toNanos(), TimeUnit.NANOSECONDS);
            return new Result(process.exitValue(), stdout, stderr);
        } catch (InterruptedException e) {
            process.destroyForcibly();
            Thread.currentThread().interrupt();
            throw new IOException("interrupted while waiting for command");
        } catch (ExecutionException e) {
            process.destroyForcibly();
            throw new IOException("cannot read command output");
        } catch (TimeoutException e) {
            process.destroyForcibly();
            throw e;
        }
    }

    private static CompletableFuture<byte[]> drain(InputStream in) {
        return CompletableFuture.supplyAsync(() -> {
            try (in) {
                return in.readAllBytes();
            } catch (IOException e) {
                throw new java.io.UncheckedIOException(e);
            }
        }, task -> Thread.ofVirtual().start(task));
    }

    private static java.io.File nullDevice() {
        return new java.io.File(System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT).contains("win")
                ? "NUL" : "/dev/null");
    }
}
