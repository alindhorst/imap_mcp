package de.alexanderlindhorst.imapmcp.core;

import static org.assertj.core.api.Assertions.assertThat;

import com.icegreen.greenmail.util.ServerSetupTest;
import org.junit.jupiter.api.Test;

/**
 * #207: GreenMail's default startup timeout of 2000 ms is exceeded under host load (parallel builds), which failed
 * unrelated tests. The parent pom raises it for every module through the surefire system property.
 */
class GreenMailStartupTimeoutTest {

    @Test
    void greenMailStartupTimeoutIsRaisedForTheBuild() {
        assertThat(ServerSetupTest.IMAPS.dynamicPort().getServerStartupTimeout()).isGreaterThanOrEqualTo(20_000L);
    }
}
