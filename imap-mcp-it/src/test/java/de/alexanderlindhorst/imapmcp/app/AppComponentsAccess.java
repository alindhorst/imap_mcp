package de.alexanderlindhorst.imapmcp.app;

import de.alexanderlindhorst.imapmcp.app.config.AppConfig;
import de.alexanderlindhorst.imapmcp.app.config.AppPaths;
import de.alexanderlindhorst.imapmcp.core.imap.pool.StorePool;
import de.alexanderlindhorst.imapmcp.store.secret.Platform;
import java.io.IOException;
import java.time.Clock;
import java.util.Map;
import java.util.Properties;
import java.util.function.UnaryOperator;

/**
 * Test-only bridge into the package-private parts of {@link AppComponents} (the session-properties seam and the
 * pool), so in-process ITs do not widen production visibility. It lives in the production package name on purpose;
 * both modules are on the test classpath, which allows package-private access (no JPMS, jar not sealed).
 */
public final class AppComponentsAccess {

    private AppComponentsAccess() {
    }

    /** Env-only secret refs (no keyring); {@code sessionHook} adds the test trust properties. */
    public static AppComponents create(AppConfig config, AppPaths paths, Map<String, String> env,
            UnaryOperator<Properties> sessionHook) {
        return AppComponents.create(config, paths, Clock.systemUTC(), env, sessionHook, Platform.LINUX,
                (argv, timeout) -> {
                    throw new IOException("no command expected");
                }, platform -> true);
    }

    public static StorePool pool(AppComponents components) {
        return components.pool();
    }
}
