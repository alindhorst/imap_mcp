package de.alexanderlindhorst.imapmcp.transport.http;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.List;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * The {@code oauth.*} settings of {@code http-public} that the resource server needs (SPEC 5.3, 5.7): the
 * issuer, the canonical resource URI (configured, never derived from request headers) and the subject claim.
 * The resource URI is {@code https} without user info, query or fragment, and its path ends with {@code /mcp}.
 */
public record OAuthResourceConfig(String issuer, String resource, String subjectClaim,
        List<String> scopesSupported) {

    public static final String METADATA_ROOT = "/.well-known/oauth-protected-resource";

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

    static final String PATH_MESSAGE = "oauth.resource: path must end with /mcp (no trailing slash)";

    /** Raw path: unreserved and sub-delimiter characters only, no empty segments, ending with {@code /mcp}. */
    private static final Pattern RESOURCE_PATH = Pattern.compile("^(/[A-Za-z0-9._~!$&'()+,=:@-]+)*/mcp$");

    /**
     * The raw path ends with {@code /mcp} (so {@code /mcp} or {@code /x/mcp}), has no empty, {@code .} or
     * {@code ..} segment, no percent-encoding and only ASCII path characters; a trailing slash is never allowed.
     */
    static boolean hasMcpPath(String rawPath) {
        if (rawPath == null || !RESOURCE_PATH.matcher(rawPath).matches()) {
            return false;
        }
        for (String segment : rawPath.split("/")) {
            if (segment.equals(".") || segment.equals("..")) {
                return false;
            }
        }
        return true;
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
        if (u.getRawAuthority().endsWith(":") || !hasMcpPath(u.getRawPath())) { // an empty port is not a port
            throw new IllegalArgumentException(PATH_MESSAGE);
        }
        return u;
    }

    /** RFC 9728 metadata path for this resource: the well-known root followed by the resource's own path. */
    public String metadataPath() {
        return METADATA_ROOT + parse(resource).getRawPath();
    }

    /** Absolute metadata URL as announced in {@code resource_metadata}; built from the configuration only. */
    public String metadataUrl() {
        URI u = parse(resource);
        return "https://" + u.getRawAuthority() + metadataPath();
    }
}
