package de.alexanderlindhorst.imapmcp.transport.http;

import com.nimbusds.jose.jwk.source.JWKSource;
import com.nimbusds.jose.proc.SecurityContext;

public final class JwtValidator {

    public JwtValidator(OAuthResourceConfig config, JWKSource<SecurityContext> keys) {
    }

    public JwtPrincipal validate(String token) throws InvalidTokenException {
        throw new UnsupportedOperationException();
    }
}
