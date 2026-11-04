package de.alexanderlindhorst.imapmcp.core;

import com.icegreen.greenmail.util.ServerSetupTest;
import org.junit.platform.launcher.LauncherSession;
import org.junit.platform.launcher.LauncherSessionListener;

/**
 * Registered via ServiceLoader (#207). GreenMail's start timeout of 2000 ms is a constant that no system property
 * changes for {@code dynamicPort()} setups; under host load it made unrelated tests fail with "Could not start mail
 * server". Raised once per test JVM on the shared setups, which {@code dynamicPort()} copies.
 */
public final class GreenMailStartupTimeoutListener implements LauncherSessionListener {

    static final long STARTUP_TIMEOUT_MS = 30_000L;

    @Override
    public void launcherSessionOpened(LauncherSession session) {
        raiseSafely(() -> {
            ServerSetupTest.IMAP.setServerStartupTimeout(STARTUP_TIMEOUT_MS);
            ServerSetupTest.IMAPS.setServerStartupTimeout(STARTUP_TIMEOUT_MS);
        });
    }

    /**
     * This class travels in the core test-jar to modules without GreenMail (imap-mcp-store); there the missing
     * classes are ignored (the surefire fork would crash otherwise).
     */
    static void raiseSafely(Runnable raise) {
        try {
            raise.run();
        } catch (LinkageError e) {
            // GreenMail is not on this module's test classpath: nothing to raise
        }
    }
}
