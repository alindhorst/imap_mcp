package de.alexanderlindhorst.imapmcp.transport.http;

import com.nimbusds.jose.KeySourceException;
import com.nimbusds.jose.jwk.JWK;
import com.nimbusds.jose.jwk.JWKSelector;
import com.nimbusds.jose.jwk.source.JWKSource;
import com.nimbusds.jose.proc.SecurityContext;
import java.time.Duration;
import java.util.List;

public final class IssuerJwks implements JWKSource<SecurityContext> {

    public static IssuerJwks create(String issuer) {
        return create(issuer, Duration.ofMinutes(5), Duration.ofSeconds(30));
    }

    public static IssuerJwks create(String issuer, Duration cacheTtl, Duration minRefreshInterval) {
        throw new UnsupportedOperationException();
    }

    @Override
    public List<JWK> get(JWKSelector selector, SecurityContext context) throws KeySourceException {
        throw new UnsupportedOperationException();
    }
}
