package de.alexanderlindhorst.imapmcp.it;

import java.util.List;
import java.util.Map;

/** RFC 9110 section 11 parser for WWW-Authenticate values. */
final class AuthParams {

    private AuthParams() {
    }

    record Challenge(String scheme, Map<String, String> params) {
        String param(String name) {
            throw new UnsupportedOperationException();
        }
    }

    static List<Challenge> parse(String header) {
        throw new UnsupportedOperationException();
    }
}
