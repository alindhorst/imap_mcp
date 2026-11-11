package de.alexanderlindhorst.imapmcp.it;

/** PKCE (RFC 7636) S256 material for the connector test client. */
final class Pkce {

    private Pkce() {
    }

    static String newVerifier() {
        throw new UnsupportedOperationException();
    }

    static String challenge(String verifier) {
        throw new UnsupportedOperationException();
    }

    static String newState() {
        throw new UnsupportedOperationException();
    }
}
