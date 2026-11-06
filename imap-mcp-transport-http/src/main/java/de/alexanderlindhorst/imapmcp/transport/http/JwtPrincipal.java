package de.alexanderlindhorst.imapmcp.transport.http;

import java.util.Map;

/** The validated identity of a request: issuer, subject claim value and all claims of the verified token. */
public record JwtPrincipal(String issuer, String subject, Map<String, Object> claims) {

    /** The issuer only: subject and claims (e-mail, names) must not end up in logs by accident. */
    @Override
    public String toString() {
        return "JwtPrincipal[issuer=" + issuer + "]";
    }
}
