package de.alexanderlindhorst.imapmcp.app;

import com.nimbusds.jose.jwk.source.JWKSource;
import com.nimbusds.jose.proc.SecurityContext;
import de.alexanderlindhorst.imapmcp.app.config.PublicConfig;
import de.alexanderlindhorst.imapmcp.transport.http.HttpLocalServer;

/** Stub. */
public final class HttpPublicServer {

    private HttpPublicServer() {
    }

    public static HttpLocalServer build(PublicConfig config, PublicComponents components,
            JWKSource<SecurityContext> keys) {
        throw new UnsupportedOperationException();
    }
}
