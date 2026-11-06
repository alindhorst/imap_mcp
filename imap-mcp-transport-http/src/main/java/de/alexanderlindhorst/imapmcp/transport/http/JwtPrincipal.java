package de.alexanderlindhorst.imapmcp.transport.http;

import java.util.Map;

/** The validated identity of a request: issuer, subject claim value and all claims of the verified token. */
public record JwtPrincipal(String issuer, String subject, Map<String, Object> claims) {
}
