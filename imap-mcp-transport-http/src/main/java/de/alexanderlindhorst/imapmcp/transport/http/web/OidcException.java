package de.alexanderlindhorst.imapmcp.transport.http.web;

/** The IdP exchange failed. {@code kind} is all there is: no response text, token or URL. */
public final class OidcException extends Exception {

    /** The IdP could not be reached, answered an error or sent no ID token: answered 502. */
    public static final String UNAVAILABLE = "unavailable";
    /** The ID token did not validate: answered 400. */
    public static final String INVALID_TOKEN = "invalid_token";

    public OidcException(String kind) {
        super(kind, null, false, false);
    }

    public String kind() {
        return getMessage();
    }
}
