package de.alexanderlindhorst.imapmcp.store.secret;

import de.alexanderlindhorst.imapmcp.core.account.Secret;
import java.time.Duration;

/** Looks up secrets in the OS keyring through its CLI ({@code secret-tool} / {@code security}). */
public final class KeyringSecretResolver {

    public KeyringSecretResolver(CommandRunner runner) {
    }

    public Secret lookup(Platform platform, String service, String account, Duration timeout) {
        throw new UnsupportedOperationException();
    }
}
