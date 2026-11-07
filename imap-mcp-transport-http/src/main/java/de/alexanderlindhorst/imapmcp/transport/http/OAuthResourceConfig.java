package de.alexanderlindhorst.imapmcp.transport.http;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.List;
import java.util.Objects;

/**
 * The {@code oauth.*} settings of {@code http-public} that the resource server needs (SPEC 5.3, 5.7): the
 * issuer, the canonical resource URI (configured, never derived from request headers) and the subject claim.
 * The resource URI is {@code https} without user info, query or fragment.
 */
public record OAuthResourceConfig(String issuer, String resource, String subjectClaim,
        List<String> scopesSupported) {

    public static final String METADATA_ROOT = "/.well-known/oauth-protected-resource";
    static final String CHALLENGE_SCOPE = "imap-mcp:read imap-mcp:write";

    public OAuthResourceConfig {
        if (issuer == null || issuer.isBlank()) {
            throw new IllegalArgumentException("oauth.issuer must not be blank");
        }
        if (subjectClaim == null || subjectClaim.isBlank()) {
            throw new IllegalArgumentException("oauth.subjectClaim must not be blank");
        }
        parse(resource);
        scopesSupported = List.copyOf(Objects.requireNonNull(scopesSupported, "scopesSupported"));
    }

    private static URI parse(String resource) {
        URI u;
        try {
            u = new URI(Objects.requireNonNull(resource, "oauth.resource"));
        } catch (URISyntaxException e) {
            throw new IllegalArgumentException("oauth.resource is not a valid URI");
        }
        if (!"https".equals(u.getScheme()) || u.getRawAuthority() == null || u.getHost() == null
                || u.getRawUserInfo() != null || u.getRawQuery() != null || u.getRawFragment() != null) {
            throw new IllegalArgumentException(
                    "oauth.resource must be an https URI without user info, query or fragment");
        }
        return u;
    }

    /** RFC 9728 metadata path for this resource: the well-known root followed by the resource's own path. */
    public String metadataPath() {
        String path = parse(resource).getRawPath();
        return path == null || path.isEmpty() || path.equals("/") ? METADATA_ROOT : METADATA_ROOT + path;
    }

    /** Absolute metadata URL as announced in {@code resource_metadata}; built from the configuration only. */
    public String metadataUrl() {
        URI u = parse(resource);
        return "https://" + u.getRawAuthority() + metadataPath();
    }

    /**
     * The {@code WWW-Authenticate} challenge of SPEC 5.3 ("Discovery and challenges"): realm, the optional
     * {@code error} ({@code invalid_token} or {@code insufficient_scope}), the metadata URL and both scopes. No
     * {@code error_description}: nothing about the token is echoed.
     */
    public String challenge(String error) {
        return "Bearer realm=\"imap-mcp\"" + (error == null ? "" : ", error=\"" + error + "\"")
                + ", resource_metadata=\"" + metadataUrl() + "\", scope=\"" + CHALLENGE_SCOPE + "\"";
    }
}
