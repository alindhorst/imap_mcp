package de.alexanderlindhorst.imapmcp.app;

import com.nimbusds.jose.jwk.source.JWKSource;
import com.nimbusds.jose.proc.SecurityContext;
import de.alexanderlindhorst.imapmcp.core.crypto.LogIds;
import de.alexanderlindhorst.imapmcp.app.config.PublicConfig;
import de.alexanderlindhorst.imapmcp.transport.http.BearerTokenFilter;
import de.alexanderlindhorst.imapmcp.transport.http.HealthServlet;
import de.alexanderlindhorst.imapmcp.transport.http.HostFilter;
import de.alexanderlindhorst.imapmcp.transport.http.HttpLocalServer;
import de.alexanderlindhorst.imapmcp.transport.http.JwtPrincipal;
import de.alexanderlindhorst.imapmcp.transport.http.JwtTokenFilter;
import de.alexanderlindhorst.imapmcp.transport.http.JwtValidator;
import de.alexanderlindhorst.imapmcp.transport.http.KeysUnavailableReporter;
import de.alexanderlindhorst.imapmcp.transport.http.OAuthResourceConfig;
import de.alexanderlindhorst.imapmcp.transport.http.OriginFilter;
import de.alexanderlindhorst.imapmcp.transport.http.PrincipalContextFilter;
import de.alexanderlindhorst.imapmcp.transport.http.PrincipalTenants;
import de.alexanderlindhorst.imapmcp.transport.http.ProtectedResourceMetadataServlet;
import de.alexanderlindhorst.imapmcp.transport.http.web.HttpOidcProvider;
import de.alexanderlindhorst.imapmcp.transport.http.web.WebConfig;
import de.alexanderlindhorst.imapmcp.transport.http.web.WebSurface;
import de.alexanderlindhorst.imapmcp.core.tenancy.Scopes;
import jakarta.servlet.http.HttpServletRequest;
import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Objects;

/**
 * Assembles the {@code http-public} HTTP server (SPEC 5.3): filter order on /mcp is Host, Origin, method guard
 * (built into the server), token ({@link JwtTokenFilter}), principal to tenant and scope
 * ({@link PrincipalContextFilter}), then, with {@code http.stateful}, the session binding (sessions belong to the
 * (issuer, subject) of the validated token, never to the tenant), then the MCP servlet (stateless, or the stateful
 * Streamable HTTP variant that carries the purge confirmation). The RFC 9728 metadata and /health are outside the filters.
 * The linking surface (C1, C2) is mounted at /accounts with its own filters (no bearer token, none of the /mcp chain);
 * it validates ID tokens against the same key source and issuer as the access tokens.
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
        return assemble(config, components, keys, false);
    }

    /**
     * Test seam: {@code allowInsecureHttpIssuer} lets the linking surface talk to an {@code http} issuer (a Keycloak container in
     * tests). Production code uses {@link #build(PublicConfig, PublicComponents, JWKSource)}, where the issuer must be
     * {@code https}.
     */
    public static HttpLocalServer buildForTest(PublicConfig config, PublicComponents components,
            JWKSource<SecurityContext> keys, boolean allowInsecureHttpIssuer) {
        return assemble(config, components, keys, allowInsecureHttpIssuer);
    }

    /**
     * The RFC 9728 metadata document at its path-inserted URL ({@code oauth.resource} always has a path ending in
     * {@code /mcp}, so that is never the root) and at the root, the fallback clients probe second (SPEC 5.3).
     */
    static HttpLocalServer.Builder metadataServlets(HttpLocalServer.Builder builder, OAuthResourceConfig oauth) {
        ProtectedResourceMetadataServlet metadata = new ProtectedResourceMetadataServlet(oauth);
        // the root is a deliberate fallback (SPEC 5.3): strictly it belongs to the resource https://<authority>,
        // so `resource` still equals oauth.resource
        return builder.openServlet(OAuthResourceConfig.METADATA_ROOT, metadata)
                .openServlet(oauth.metadataPath(), metadata);
    }

    private static HttpLocalServer assemble(PublicConfig config, PublicComponents components,
            JWKSource<SecurityContext> keys, boolean allowInsecureHttpIssuer) {
        Objects.requireNonNull(keys, "keys");
        PublicConfig.Oauth o = config.oauth();
        OAuthResourceConfig oauth = new OAuthResourceConfig(o.issuer(), o.resource(), o.subjectClaim(),
                List.of(Scopes.READ, Scopes.WRITE));
        PublicConfig.Http h = config.http();
        PrincipalTenants tenants = (issuer, subject) -> components.tenantDirectory().find(issuer, subject)
                .map(t -> new PrincipalTenants.Tenant(t.tenantId(), t.disabled()));
        // one reporter for /mcp and the linking callback: one counter, one WARN per minute (D16)
        KeysUnavailableReporter keysUnavailable = new KeysUnavailableReporter(components.metrics(),
                () -> System.nanoTime() / 1_000_000); // monotonic: a wall-clock step must not reopen the WARN
        HttpLocalServer.Builder builder = HttpLocalServer.builder()
                .maxRequestBytes((int) Math.min(h.maxRequestBytes(), Integer.MAX_VALUE))
                .serverInfo(AppVersion.SERVER_NAME, AppVersion.version())
                .bind(h.bind()).port(h.port()).allowNonLoopbackBind(h.allowNonLoopbackBind())
                .hostFilter(new HostFilter(h.allowedHosts()))
                .originFilter(new OriginFilter(h.allowedOrigins()))
                .bearerFilter(new JwtTokenFilter(new JwtValidator(oauth, keys), oauth, keysUnavailable))
                .principalFilter(new PrincipalContextFilter(tenants, oauth, new LogIds(components.keyRing())))
                .contextExtractor(BearerTokenFilter.contextExtractor())
                .healthServlet(new HealthServlet())
                .webSurface(webSurface(config, components, keys, allowInsecureHttpIssuer, keysUnavailable));
        metadataServlets(builder, oauth);
        if (h.stateful()) {
            // Stateful Streamable HTTP (SPEC 4 v3b, D8): sessions are bound to (issuer, subject) of the validated token,
            // never to the tenant (unknown principals share one sentinel tenant); the SDK's server-to-client request
            // timeout is above the longest tool deadline (SPEC 5.5).
            builder.stateful(true).sessionPrincipal(HttpPublicServer::sessionPrincipal)
                    .sessionEndListener(components.sessionEndListener()).requestTimeout(STATEFUL_REQUEST_TIMEOUT)
                    .initializeRateLimit(h.initializeRatePerMinute(), Clock.systemUTC())
                    .metrics(components.metrics());
            components.statefulToolSpecs(BearerTokenFilter.accountResolver()).forEach(builder::statefulTool);
        } else {
            components.statelessToolSpecs(BearerTokenFilter.accountResolver()).forEach(builder::tool);
        }
        return builder.build();
    }

    /**
     * The linking surface: the web client, the public base URL and the role rule from {@code oauth.*} and
     * {@code public.*}; the ID token is checked against the same key source as the access tokens.
     */
    static WebSurface webSurface(PublicConfig config, PublicComponents components, JWKSource<SecurityContext> keys,
            boolean allowInsecureHttpIssuer, KeysUnavailableReporter keysUnavailable) {
        PublicConfig.Oauth o = config.oauth();
        WebConfig web = new WebConfig(URI.create(config.pub().baseUrl()), o.issuer(), o.webClientId(),
                components.webClientSecret(), o.subjectClaim(), o.requiredRole(), o.roleClaim(),
                o.allowWithoutRole(), allowInsecureHttpIssuer);
        return WebSurface.of(web, HttpOidcProvider.create(web, keys), components.oidcPendingStore(),
                components.webSessionStore(), components.tenantProvisioner(), components.accountLinker(),
                components.clock(), keysUnavailable, components.upstreamLinker());
    }
}
