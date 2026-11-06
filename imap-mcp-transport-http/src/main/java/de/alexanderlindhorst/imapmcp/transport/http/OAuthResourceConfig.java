package de.alexanderlindhorst.imapmcp.transport.http;

import java.util.List;

/**
 * The {@code oauth.*} settings of {@code http-public} that the resource server needs (SPEC 5.3, 5.7): the
 * issuer, the canonical resource URI (configured, never derived from request headers) and the subject claim.
 */
public record OAuthResourceConfig(String issuer, String resource, String subjectClaim,
        List<String> scopesSupported) {

    public String metadataUrl() {
        throw new UnsupportedOperationException();
    }

    public String metadataPath() {
        throw new UnsupportedOperationException();
    }
}
