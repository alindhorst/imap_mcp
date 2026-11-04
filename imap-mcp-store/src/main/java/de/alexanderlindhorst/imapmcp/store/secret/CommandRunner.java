package de.alexanderlindhorst.imapmcp.store.secret;

import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeoutException;

/** Seam for running an external command without a shell; tests substitute a fake. */
public interface CommandRunner {

    /**
     * Runs {@code argv} (no shell), stdin closed, environment inherited.
     *
     * @throws TimeoutException if the command did not finish in time (the process has been destroyed)
     * @throws IOException      if the command could not be started or its output not read
     */
    Result run(List<String> argv, Duration timeout) throws IOException, TimeoutException;

    /**
     * Outcome of a finished command. {@code stdout} and {@code stderr} are owned by the receiver, who must wipe them.
     * stderr may carry secrets (macOS {@code security -g}); it is only ever parsed, never logged: only its length
     * appears in {@link #toString()}.
     */
    record Result(int exitCode, byte[] stdout, byte[] stderr) {

        /** Output-less stderr of a given length (callers that only care about the size). */
        public Result(int exitCode, byte[] stdout, int stderrLength) {
            this(exitCode, stdout, new byte[stderrLength]);
        }

        public int stderrLength() {
            return stderr.length;
        }

        @Override
        public String toString() {
            return "Result[exitCode=" + exitCode + ", stderrLength=" + stderr.length + "]";
        }
    }
}
