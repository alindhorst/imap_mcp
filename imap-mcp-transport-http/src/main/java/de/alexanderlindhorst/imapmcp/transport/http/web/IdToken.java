package de.alexanderlindhorst.imapmcp.transport.http.web;

import java.time.Instant;
import java.util.Map;

/** A validated ID token: issuer, the configured subject claim, {@code auth_time} (null if absent) and all claims. */
public record IdToken(String issuer, String subject, Instant authTime, Map<String, Object> claims) {

    @Override
    public String toString() {
        return "IdToken[issuer=" + issuer + "]";
    }
}
