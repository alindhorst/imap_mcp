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
                .hasMessageContaining("-DskipITs");
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
}
