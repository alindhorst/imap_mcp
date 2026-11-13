package de.alexanderlindhorst.imapmcp.store.oauth;

import java.net.URI;

/** Skeleton (red commit). */
public final class TokenEndpointClient implements TokenEndpoint {

    TokenEndpointClient(java.util.function.Predicate<java.net.InetAddress> blocked,
            de.alexanderlindhorst.imapmcp.core.net.PinnedSocketFactory.Resolver resolver,
            javax.net.ssl.SSLContext ssl, int port, java.time.Duration connect, java.time.Duration total,
            java.time.Clock clock) {
    }

    @Override
    public TokenResponse refresh(TokenProvider provider, char[] refreshToken) {
        throw new UnsupportedOperationException();
    }

    @Override
    public TokenResponse exchangeCode(TokenProvider provider, char[] code, char[] codeVerifier, URI redirectUri) {
        throw new UnsupportedOperationException();
    }
}
