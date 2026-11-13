package de.alexanderlindhorst.imapmcp.store.oauth;

import java.net.URI;

/** The two grants the server sends to an upstream token endpoint (SPEC 5.3, 5.5). */
public interface TokenEndpoint {

    /** Refresh-token grant. {@code refreshToken} is not retained or wiped. */
    TokenResponse refresh(TokenProvider provider, char[] refreshToken);

    /** Authorization-code exchange with the PKCE verifier (X1-2). Inputs are not retained or wiped. */
    TokenResponse exchangeCode(TokenProvider provider, char[] code, char[] codeVerifier, URI redirectUri);
}
