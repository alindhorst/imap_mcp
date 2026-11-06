package de.alexanderlindhorst.imapmcp.store.crypto;

/**
 * The call was cancelled or ran into its deadline while waiting for the key service. Not a statement about the key
 * service: it passes like {@link KeyServiceUnavailableException} (never a decrypt failure) but is neither logged
 * nor counted as unavailability. The thread's interrupt flag is set again by whoever throws it.
 */
public final class KeyServiceInterruptedException extends KeyServiceUnavailableException {

    public KeyServiceInterruptedException(String provider) {
        super(provider, "interrupted");
    }
}
