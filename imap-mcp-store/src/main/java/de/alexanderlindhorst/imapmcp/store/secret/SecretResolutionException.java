package de.alexanderlindhorst.imapmcp.store.secret;

/** A secret reference is malformed or cannot be resolved. Messages name kind and variable/path, never a value. */
public class SecretResolutionException extends RuntimeException {

    public SecretResolutionException(String message) {
        super(message);
    }
}
