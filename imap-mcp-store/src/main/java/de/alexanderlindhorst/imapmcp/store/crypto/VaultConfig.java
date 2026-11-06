package de.alexanderlindhorst.imapmcp.store.crypto;

import java.net.URI;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Vault Transit settings (SPEC 5.7, {@code kms.vault.*}). {@code address} is a base URL without path, query or
 * user info; {@code https} unless {@code allowInsecureHttp} (tests only). {@code caCert}, when set, replaces the JDK
 * trust store for Vault; host name verification is always on.
 */
public record VaultConfig(URI address, String transitMount, String keyName, VaultAuth auth, Path caCert,
        Duration requestTimeout, boolean allowInsecureHttp) {

    public static final Duration DEFAULT_REQUEST_TIMEOUT = Duration.ofSeconds(5);
    private static final Duration MIN_TIMEOUT = Duration.ofSeconds(1);
    private static final Duration MAX_TIMEOUT = Duration.ofSeconds(30);
    private static final Pattern MOUNT = Pattern.compile("[A-Za-z0-9_-]{1,64}(/[A-Za-z0-9_-]{1,64})*");
    /** Vault's key-name grammar: no leading or trailing dot or dash, so no {@code .} or {@code ..} path segment. */
    static final Pattern KEY_NAME = Pattern.compile("[A-Za-z0-9_](?:[A-Za-z0-9_.-]{0,62}[A-Za-z0-9_])?");

    public VaultConfig {
        Objects.requireNonNull(address, "address");
        Objects.requireNonNull(transitMount, "transitMount");
        Objects.requireNonNull(keyName, "keyName");
        Objects.requireNonNull(auth, "auth");
        Objects.requireNonNull(requestTimeout, "requestTimeout");
        String scheme = address.getScheme();
        boolean https = "https".equals(scheme);
        if (!https && !("http".equals(scheme) && allowInsecureHttp)) {
            throw new IllegalArgumentException("kms.vault.address must be an https URL");
        }
        String path = address.getRawPath();
        if (address.getHost() == null || address.getRawUserInfo() != null || address.getRawQuery() != null
                || address.getRawFragment() != null || (path != null && !path.isEmpty() && !"/".equals(path))) {
            throw new IllegalArgumentException("kms.vault.address must be a base URL (scheme, host, port)");
        }
        if (!MOUNT.matcher(transitMount).matches()) {
            throw new IllegalArgumentException("kms.vault.transitMount is malformed");
        }
        if (!KEY_NAME.matcher(keyName).matches()) {
            throw new IllegalArgumentException("kms.vault.keyName is malformed");
        }
        if (requestTimeout.compareTo(MIN_TIMEOUT) < 0 || requestTimeout.compareTo(MAX_TIMEOUT) > 0) {
            throw new IllegalArgumentException("kms.vault.requestTimeout must be between 1s and 30s");
        }
    }

    @Override
    public String toString() {
        return "VaultConfig[" + address + ", mount=" + transitMount + ", key=" + keyName + "]";
    }
}
