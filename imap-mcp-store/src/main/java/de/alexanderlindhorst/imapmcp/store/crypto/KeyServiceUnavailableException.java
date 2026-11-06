package de.alexanderlindhorst.imapmcp.store.crypto;

/**
 * The key service (for example Vault Transit) could not be reached or answered with an error. This says something
 * about availability only, never about the integrity of a stored credential: it is neither logged nor counted as a
 * decrypt failure. The message is fixed.
 */
public class KeyServiceUnavailableException extends KeyWrapException {

    public KeyServiceUnavailableException() {
        super("key service unavailable");
    }
}
