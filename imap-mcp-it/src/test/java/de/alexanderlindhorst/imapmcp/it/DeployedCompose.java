package de.alexanderlindhorst.imapmcp.it;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

/**
 * One compose project under Testcontainers ({@code ComposeContainer}, local compose): started with a bound, compose
 * logs saved under {@code <dir>/logs} when the start fails, torn down with {@code down -v}. Ryuk (registered by
 * Testcontainers for the project label) is the backstop when the JVM dies.
 */
final class DeployedCompose {

    DeployedCompose(String project, Path dir, List<String> files) {
        throw new UnsupportedOperationException();
    }

    String project() {
        throw new UnsupportedOperationException();
    }

    void start(Duration bound) {
        throw new UnsupportedOperationException();
    }

    void stop() {
        throw new UnsupportedOperationException();
    }

    int hostPort(String service, int containerPort) {
        throw new UnsupportedOperationException();
    }

    /** Containers, networks and volumes of the project that still exist (descriptions without any content). */
    List<String> leftovers() {
        throw new UnsupportedOperationException();
    }

    Path logsDir() {
        throw new UnsupportedOperationException();
    }
}
