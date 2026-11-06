package de.alexanderlindhorst.imapmcp.transport.http;

/** A presented bearer token failed validation. The message is a fixed category; it never carries token content. */
public final class InvalidTokenException extends Exception {

    public InvalidTokenException(String category) {
        super(category, null, false, false);
    }
}
