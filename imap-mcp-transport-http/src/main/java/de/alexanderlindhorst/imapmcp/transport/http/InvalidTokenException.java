package de.alexanderlindhorst.imapmcp.transport.http;

/**
 * A presented bearer token failed validation. The message is one of the fixed categories below; it never
 * carries token content, claim values or the validation library's text, and there is no cause.
 */
public final class InvalidTokenException extends Exception {

    public static final String MALFORMED = "malformed";
    public static final String REJECTED = "rejected";
    /** The signing keys could not be obtained (identity provider unreachable); not the token's fault. */
    public static final String KEYS_UNAVAILABLE = "keys_unavailable";

    public InvalidTokenException(String category) {
        super(category, null, false, false);
    }

    public String category() {
        return getMessage();
    }
}
