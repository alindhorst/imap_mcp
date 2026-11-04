package de.alexanderlindhorst.imapmcp.core;

import static org.assertj.core.api.Assertions.assertThatCode;

import org.junit.jupiter.api.Test;

/**
 * #207: the listener is exported to imap-mcp-store through the core test-jar, whose test classpath has no GreenMail;
 * the missing class must be ignored there instead of crashing the surefire fork.
 */
class GreenMailStartupTimeoutListenerTest {

    @Test
    void missingGreenMailClassesAreIgnored() {
        assertThatCode(() -> GreenMailStartupTimeoutListener.raiseSafely(() -> {
            throw new NoClassDefFoundError("com/icegreen/greenmail/util/ServerSetupTest");
        })).doesNotThrowAnyException();
    }
}
