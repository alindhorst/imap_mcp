package de.alexanderlindhorst.imapmcp.app;

import static org.assertj.core.api.Assertions.assertThat;

import com.icegreen.greenmail.util.ServerSetupTest;
import org.junit.jupiter.api.Test;

/**
 * #207: GreenMail's default startup timeout of 2000 ms is exceeded under host load (parallel builds), which failed
 * unrelated tests. The test listener of the module raises it (no system property reaches dynamicPort() setups).
 */
class GreenMailStartupTimeoutTest {

    @Test
    void greenMailStartupTimeoutIsRaisedForTheBuild() {
        assertThat(ServerSetupTest.IMAPS.dynamicPort().getServerStartupTimeout()).isGreaterThanOrEqualTo(20_000L);
    }
}
