package de.alexanderlindhorst.imapmcp.store.crypto;

import de.alexanderlindhorst.imapmcp.core.error.ServiceOutage;

/**
 * The key service (for example Vault Transit) could not be reached or answered with an error. This says something
 * about availability only, never about the integrity of a stored credential: it gets its own WARN line and metric
 * and is never reported as a decrypt failure. {@code provider} and {@code failureClass} come from a fixed set
 * (for example {@code vault} and {@code timeout}, {@code tls}, {@code unreachable}, {@code http_503}); the message
 * is fixed and carries no response text, token or id. {@link ServiceOutage} makes the tool executor log it at WARN.
 */
public class KeyServiceUnavailableException extends KeyWrapException implements ServiceOutage {

    private final String provider;
    private final String failureClass;

    public KeyServiceUnavailableException() {
        this("unknown", "unknown");
    }

    public KeyServiceUnavailableException(String provider, String failureClass) {
        super("key service unavailable");
        this.provider = provider;
        this.failureClass = failureClass;
    }

    public String provider() {
        return provider;
    }

    public String failureClass() {
        return failureClass;
    }
}
