package de.alexanderlindhorst.imapmcp.app;

import com.nimbusds.jose.jwk.source.JWKSource;
import com.nimbusds.jose.proc.SecurityContext;
import de.alexanderlindhorst.imapmcp.app.config.PublicConfig;
import de.alexanderlindhorst.imapmcp.transport.http.BearerTokenFilter;
import de.alexanderlindhorst.imapmcp.transport.http.HealthServlet;
import de.alexanderlindhorst.imapmcp.transport.http.HostFilter;
import de.alexanderlindhorst.imapmcp.transport.http.HttpLocalServer;
import de.alexanderlindhorst.imapmcp.transport.http.JwtPrincipal;
import de.alexanderlindhorst.imapmcp.transport.http.JwtTokenFilter;
import de.alexanderlindhorst.imapmcp.transport.http.JwtValidator;
import de.alexanderlindhorst.imapmcp.transport.http.OAuthResourceConfig;
import de.alexanderlindhorst.imapmcp.transport.http.OriginFilter;
import de.alexanderlindhorst.imapmcp.transport.http.PrincipalContextFilter;
import de.alexanderlindhorst.imapmcp.transport.http.PrincipalTenants;
import de.alexanderlindhorst.imapmcp.transport.http.ProtectedResourceMetadataServlet;
import de.alexanderlindhorst.imapmcp.core.tenancy.Scopes;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Duration;
import java.util.List;
import java.util.Objects;

/**
 * Assembles the {@code http-public} HTTP server (SPEC 5.3): filter order on /mcp is Host, Origin, method guard
 * (built into the server), token ({@link JwtTokenFilter}), principal to tenant and scope
 * ({@link PrincipalContextFilter}), then, with {@code http.stateful}, the session binding (sessions belong to the
 * (issuer, subject) of the validated token, never to the tenant), then the MCP servlet (stateless, or the stateful
 * Streamable HTTP variant that carries the purge confirmation). The RFC 9728 metadata and /health are outside the filters.
 * The only place in {@code app} that connects the OAuth settings to the transport; the key source is a parameter so
 * that tests can sign tokens locally, while the launcher passes {@code IssuerJwks.create(oauth.issuer)}.
 */
public final class HttpPublicServer {

    private static final Duration STATEFUL_REQUEST_TIMEOUT = Duration.ofMinutes(11);

    private HttpPublicServer() {
    }

    /** Issuer and subject of the validated token; null (no session) when the token filter did not run. */
    static String sessionPrincipal(HttpServletRequest request) {
        return request.getAttribute(JwtTokenFilter.PRINCIPAL_ATTRIBUTE) instanceof JwtPrincipal p
                ? p.issuer() + "\n" + p.subject() : null;
    }

    public static HttpLocalServer build(PublicConfig config, PublicComponents components,
            JWKSource<SecurityContext> keys) {
        Objects.requireNonNull(keys, "keys");
        PublicConfig.Oauth o = config.oauth();
        OAuthResourceConfig oauth = new OAuthResourceConfig(o.issuer(), o.resource(), o.subjectClaim(),
                List.of(Scopes.READ, Scopes.WRITE));
        PublicConfig.Http h = config.http();
        PrincipalTenants tenants = (issuer, subject) -> components.tenantDirectory().find(issuer, subject)
                .map(t -> new PrincipalTenants.Tenant(t.tenantId(), t.disabled()));
        ProtectedResourceMetadataServlet metadata = new ProtectedResourceMetadataServlet(oauth);
        HttpLocalServer.Builder builder = HttpLocalServer.builder()
                .maxRequestBytes((int) Math.min(h.maxRequestBytes(), Integer.MAX_VALUE))
                .serverInfo(AppVersion.SERVER_NAME, AppVersion.version())
                .bind(h.bind()).port(h.port()).allowNonLoopbackBind(h.allowNonLoopbackBind())
                .hostFilter(new HostFilter(h.allowedHosts()))
                .originFilter(new OriginFilter(h.allowedOrigins()))
                .bearerFilter(new JwtTokenFilter(new JwtValidator(oauth, keys), oauth))
                .principalFilter(new PrincipalContextFilter(tenants))
                .contextExtractor(BearerTokenFilter.contextExtractor())
                .healthServlet(new HealthServlet())
                .openServlet(OAuthResourceConfig.METADATA_ROOT, metadata);
        if (!oauth.metadataPath().equals(OAuthResourceConfig.METADATA_ROOT)) {
            builder.openServlet(oauth.metadataPath(), metadata);
        }
        if (h.stateful()) {
            // Stateful Streamable HTTP (SPEC 4 v3b, D8): sessions are bound to (issuer, subject) of the validated token,
            // never to the tenant (unknown principals share one sentinel tenant); the SDK's server-to-client request
            // timeout is above the longest tool deadline (SPEC 5.5).
            builder.stateful(true).sessionPrincipal(HttpPublicServer::sessionPrincipal)
                    .sessionEndListener(components.sessionEndListener()).requestTimeout(STATEFUL_REQUEST_TIMEOUT);
            components.statefulToolSpecs(BearerTokenFilter.accountResolver()).forEach(builder::statefulTool);
        } else {
            components.statelessToolSpecs(BearerTokenFilter.accountResolver()).forEach(builder::tool);
        }
        return builder.build();
    }
}
