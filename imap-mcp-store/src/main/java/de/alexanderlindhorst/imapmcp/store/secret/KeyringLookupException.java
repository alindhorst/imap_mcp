package de.alexanderlindhorst.imapmcp.store.secret;

/** An OS keyring lookup failed. Messages name service and account, never a value. */
public class KeyringLookupException extends SecretResolutionException {

    public enum Kind {
        UNSUPPORTED_PLATFORM,
        NOT_FOUND,
        TIMEOUT,
        COMMAND_UNAVAILABLE,
        FAILED
    }

    private final Kind kind;

    public KeyringLookupException(Kind kind, String message) {
        super(message);
        this.kind = kind;
    }

    public Kind kind() {
        return kind;
    }
}
