package de.alexanderlindhorst.imapmcp.store.crypto;

import de.alexanderlindhorst.imapmcp.core.error.ServiceOutage;

/**
 * The key service (for example Vault Transit) could not be reached or answered with an error. This says something
 * about availability only, never about the integrity of a stored credential: it is neither logged nor counted as a
 * decrypt failure. The message is fixed. {@link ServiceOutage} makes the tool executor log it at WARN.
 */
public class KeyServiceUnavailableException extends KeyWrapException implements ServiceOutage {

    public KeyServiceUnavailableException() {
        super("key service unavailable");
    }
}
