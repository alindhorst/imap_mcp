package de.alexanderlindhorst.imapmcp.store.crypto;

/** A DEK could not be wrapped or unwrapped. The message never contains key material, ids or secrets. */
public class KeyWrapException extends RuntimeException {

    public KeyWrapException(String message) {
        super(message);
    }
}
