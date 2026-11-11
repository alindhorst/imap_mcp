package de.alexanderlindhorst.imapmcp.it;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Map;

/** Copy of dist/docker that a run works in, so .env and bind mounts never resolve in the source tree. */
final class DeployedRunCopy {

    private DeployedRunCopy() {
    }

    static void copy(Path source, Path target) throws IOException {
        throw new UnsupportedOperationException();
    }

    /** Relative path to size and modification time, to prove a tree stayed untouched. */
    static Map<String, String> snapshot(Path root) throws IOException {
        throw new UnsupportedOperationException();
    }
}
