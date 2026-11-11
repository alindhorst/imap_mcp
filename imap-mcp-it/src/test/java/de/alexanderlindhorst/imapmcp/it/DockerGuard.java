package de.alexanderlindhorst.imapmcp.it;

import java.util.function.BooleanSupplier;
import org.testcontainers.DockerClientFactory;

/**
 * Makes the integration test phase fail (not skip) when Docker is missing. Failsafe sets
 * {@code imapmcp.requireDocker=true}; {@code -DskipITs} skips failsafe altogether and therefore the check; the
 * test-completeness gate (SPEC 5.8) then fails {@code verify} unless {@code -Dimapmcp.partialBuild=true} is given.
 */
public final class DockerGuard {

    static final String PROPERTY = "imapmcp.requireDocker";

    /** Test seam for {@link DockerGuardTest}: the real probe unless a test replaces it. */
    static volatile BooleanSupplier dockerProbe = () -> DockerClientFactory.instance().isDockerAvailable();

    private DockerGuard() {
    }

    static void require(boolean required, BooleanSupplier dockerAvailable) {
        if (!required) {
            return;
        }
        boolean available;
        try {
            available = dockerAvailable.getAsBoolean();
        } catch (RuntimeException e) {
            available = false;
        }
        if (!available) {
            throw new IllegalStateException("Docker is required for the integration tests (imap-mcp-it) but no "
                    + "usable Docker daemon was found. Start Docker, or run a partial build with -DskipITs -Dimapmcp.partialBuild=true (no evidence for the merge gate).");
        }
    }

    /** Called once per test run by {@link DockerGuardListener}. */
    static void requireFromSystemProperties() {
        require(Boolean.getBoolean(PROPERTY), dockerProbe);
    }
}
