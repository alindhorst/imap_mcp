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
     * Outcome of a finished command. {@code stdout} is owned by the receiver, who must wipe it; stderr content is
     * deliberately not exposed, only its length.
     */
    record Result(int exitCode, byte[] stdout, int stderrLength) {

        @Override
        public String toString() {
            return "Result[exitCode=" + exitCode + ", stderrLength=" + stderrLength + "]";
        }
    }
}
