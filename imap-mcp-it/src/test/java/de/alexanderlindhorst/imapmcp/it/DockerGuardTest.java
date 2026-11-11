package de.alexanderlindhorst.imapmcp.it;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class DockerGuardTest {

    @Test
    void failsWithClearMessageWhenDockerIsMissing() {
        assertThatThrownBy(() -> DockerGuard.require(true, () -> false))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Docker is required")
                .hasMessageContaining("-DskipITs")
                .hasMessageContaining("-Dimapmcp.partialBuild=true");
    }

    @Test
    void passesWhenDockerIsAvailable() {
        assertThatCode(() -> DockerGuard.require(true, () -> true)).doesNotThrowAnyException();
    }

    @Test
    void doesNotProbeDockerWhenNotRequired() {
        boolean[] probed = {false};
        DockerGuard.require(false, () -> {
            probed[0] = true;
            return false;
        });
        assertThat(probed[0]).isFalse();
    }

    @Test
    void probeFailureCountsAsMissingDocker() {
        assertThatThrownBy(() -> DockerGuard.require(true, () -> {
            throw new IllegalStateException("no socket");
        })).isInstanceOf(IllegalStateException.class).hasMessageContaining("Docker is required");
    }

    /**
     * #51 AC2 end to end: the listener is registered through the ServiceLoader and opening a Launcher session with
     * {@code imapmcp.requireDocker=true} and a probe that reports no Docker fails the run; without the property it
     * does not, and the probe is not even consulted.
     */
    @Test
    void openingALauncherSessionFailsWhenRequiredAndDockerIsMissing() {
        String old = System.getProperty(DockerGuard.PROPERTY);
        var oldProbe = DockerGuard.dockerProbe;
        try {
            System.setProperty(DockerGuard.PROPERTY, "true");
            DockerGuard.dockerProbe = () -> false;
            assertThatThrownBy(() -> org.junit.platform.launcher.core.LauncherFactory.openSession().close())
                    .hasStackTraceContaining("Docker is required");

            DockerGuard.dockerProbe = () -> true;
            assertThatCode(() -> org.junit.platform.launcher.core.LauncherFactory.openSession().close())
                    .doesNotThrowAnyException();

            System.clearProperty(DockerGuard.PROPERTY);
            boolean[] probed = {false};
            DockerGuard.dockerProbe = () -> {
                probed[0] = true;
                return false;
            };
            assertThatCode(() -> org.junit.platform.launcher.core.LauncherFactory.openSession().close())
                    .doesNotThrowAnyException();
            assertThat(probed[0]).isFalse();
        } finally {
            DockerGuard.dockerProbe = oldProbe;
            if (old == null) {
                System.clearProperty(DockerGuard.PROPERTY);
            } else {
                System.setProperty(DockerGuard.PROPERTY, old);
            }
        }
    }
}
