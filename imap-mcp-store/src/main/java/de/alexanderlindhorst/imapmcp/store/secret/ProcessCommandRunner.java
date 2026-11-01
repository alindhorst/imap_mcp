package de.alexanderlindhorst.imapmcp.store.secret;

import java.io.IOException;
import java.time.Duration;
import java.util.List;

/** Default {@link CommandRunner} backed by {@link ProcessBuilder}. */
public final class ProcessCommandRunner implements CommandRunner {

    @Override
    public Result run(List<String> argv, Duration timeout) throws IOException {
        throw new UnsupportedOperationException();
    }
}
