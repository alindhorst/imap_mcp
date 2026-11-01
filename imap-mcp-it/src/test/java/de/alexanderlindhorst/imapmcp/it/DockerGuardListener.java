package de.alexanderlindhorst.imapmcp.it;

import org.junit.platform.launcher.LauncherSession;
import org.junit.platform.launcher.LauncherSessionListener;

/** Registered via ServiceLoader; fails the whole failsafe run up front when Docker is not available. */
public final class DockerGuardListener implements LauncherSessionListener {

    @Override
    public void launcherSessionOpened(LauncherSession session) {
        DockerGuard.requireFromSystemProperties();
    }
}
