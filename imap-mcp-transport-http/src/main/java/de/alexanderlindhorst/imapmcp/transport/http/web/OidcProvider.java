package de.alexanderlindhorst.imapmcp.transport.http.web;

import java.net.URI;

/** The identity provider as the linking surface uses it: where to send the browser and the code exchange. */
public interface OidcProvider {

    /** The authorization endpoint from the issuer's metadata; {@link OidcException#UNAVAILABLE} if unknown. */
    URI authorizationEndpoint() throws OidcException;

    /**
     * Exchanges the authorization code (with the PKCE verifier) at the token endpoint, authenticating the
     * confidential client, and returns the validated ID token for the nonce. Access and refresh tokens are dropped.
     */
    IdToken exchange(String code, String pkceVerifier, String nonce) throws OidcException;
}
