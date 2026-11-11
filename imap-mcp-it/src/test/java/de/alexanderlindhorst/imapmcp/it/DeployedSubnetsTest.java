package de.alexanderlindhorst.imapmcp.it;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

class DeployedSubnetsTest {

    @Test
    void podmanOverlapMessageIsRecognised() {
        assertThat(DeployedSubnets.isOverlap(
                "Error response from daemon: subnet 192.31.196.0/28 is already used on the host or by another config"))
                .isTrue();
    }

    @Test
    void dockerOverlapMessageIsRecognised() {
        assertThat(DeployedSubnets.isOverlap(
                "invalid pool request: Pool overlaps with other one on this address space")).isTrue();
    }

    @Test
    void otherErrorsAreNotOverlaps() {
        assertThat(DeployedSubnets.isOverlap("manifest unknown")).isFalse();
        assertThat(DeployedSubnets.isOverlap(null)).isFalse();
    }

    @Test
    void defaultCandidatesAreTheTwoAs112Ranges() {
        assertThat(DeployedSubnets.candidates(null)).containsExactly("192.31.196.0/28", "192.175.48.0/28");
    }

    @Test
    void propertyOverridesCandidatesInOrder() {
        assertThat(DeployedSubnets.candidates(" 192.175.48.0/28 , 192.31.196.0/28 "))
                .containsExactly("192.175.48.0/28", "192.31.196.0/28");
    }

    @Test
    void everyDefaultCandidateIsOutsideTheBlocklistAndHasAtLeastSixHosts() {
        for (String cidr : DeployedSubnets.candidates(null)) {
            DeployedSubnets.requireUsable(cidr);
        }
    }

    @Test
    void aBlockedSubnetIsRefused() {
        for (String bad : List.of("172.28.0.0/24", "10.1.0.0/28", "192.168.0.0/28", "100.64.0.0/28")) {
            org.assertj.core.api.Assertions.assertThatThrownBy(() -> DeployedSubnets.requireUsable(bad))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }
}
