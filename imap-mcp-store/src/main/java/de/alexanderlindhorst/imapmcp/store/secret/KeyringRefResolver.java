package de.alexanderlindhorst.imapmcp.store.secret;

import de.alexanderlindhorst.imapmcp.core.account.Secret;
import java.time.Duration;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

/**
 * {@code keyring:<service>/<account>} references (split at the first {@code /}). Unlike env:/file: these are
 * resolved eagerly and once, in {@link #validate}, and held in memory, so a locked keyring or an OS prompt shows up
 * at a predictable time instead of on every pool reconnect. {@link #resolve} never runs a command.
 */
final class KeyringRefResolver implements SecretResolver {

    private static final int MAX_PART = 255;

    private final Platform platform;
    private final BinaryProbe probe;
    private final Duration timeout;
    private final KeyringSecretResolver lookup;
    private final Map<String, Secret> cache = new ConcurrentHashMap<>();

    KeyringRefResolver(Platform platform, CommandRunner runner, BinaryProbe probe, Duration timeout) {
        this.platform = Objects.requireNonNull(platform, "platform");
        this.probe = Objects.requireNonNull(probe, "probe");
        this.timeout = Objects.requireNonNull(timeout, "timeout");
        this.lookup = new KeyringSecretResolver(Objects.requireNonNull(runner, "runner"));
    }

    @Override
    public void validate(String target) {
        String[] parts = parse(target);
        if (cache.containsKey(target)) {
            return;
        }
        if (platform == Platform.WINDOWS) {
            throw new SecretResolutionException(
                    "keyring refs are not supported on Windows in this version; use env: or file:");
        }
        if (!probe.available(platform)) {
            throw new SecretResolutionException(
                    "keyring not available here (no secret-tool/security); use env:, a Docker secret or file:");
        }
        cache.put(target, lookup.lookup(platform, parts[0], parts[1], timeout));
    }

    @Override
    public Secret resolve(String target) {
        Secret cached = cache.get(target);
        if (cached == null) {
            throw new SecretResolutionException(
                    "keyring reference has not been validated at startup; keyring secrets are resolved only once, eagerly");
        }
        return new Secret(cached.chars().clone());
    }

    private static String[] parse(String target) {
        int slash = target.indexOf('/');
        if (slash <= 0 || slash == target.length() - 1) {
            throw new SecretResolutionException("keyring: reference must have the form keyring:<service>/<account>");
        }
        String service = target.substring(0, slash);
        String account = target.substring(slash + 1);
        for (String part : new String[] {service, account}) {
            if (part.length() > MAX_PART) {
                throw new SecretResolutionException("keyring: service and account may be at most 255 characters");
            }
            if (part.chars().anyMatch(Character::isISOControl)) {
                throw new SecretResolutionException("keyring: service and account must not contain control characters");
            }
        }
        return new String[] {service, account};
    }
}
