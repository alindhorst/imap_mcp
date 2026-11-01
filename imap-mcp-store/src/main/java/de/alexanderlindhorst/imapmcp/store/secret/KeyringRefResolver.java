package de.alexanderlindhorst.imapmcp.store.secret;

import de.alexanderlindhorst.imapmcp.core.account.Secret;
import java.time.Duration;

/** {@code keyring:<service>/<account>} references. */
final class KeyringRefResolver implements SecretResolver {

    KeyringRefResolver(Platform platform, CommandRunner runner, BinaryProbe probe, Duration timeout) {
    }

    @Override
    public void validate(String target) {
        throw new UnsupportedOperationException();
    }

    @Override
    public Secret resolve(String target) {
        throw new UnsupportedOperationException();
    }
}
