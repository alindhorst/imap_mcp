package de.alexanderlindhorst.imapmcp.transport.http.web;

import de.alexanderlindhorst.imapmcp.core.account.Secret;
import java.net.URI;
import java.util.Locale;
import java.util.Objects;
import java.util.function.Supplier;

/**
 * Settings of the linking surface (SPEC 5.3, 5.7): the public base URL (configured, never derived from request
 * headers), the OIDC issuer and the confidential web client, the subject claim and the tenant-creation role.
 * {@code clientSecret} is read per use and the {@link Secret} is closed by the caller. No role check
 * ({@code requiredRole} null) is accepted only together with {@code allowWithoutRole}.
 */
public record WebConfig(URI publicBaseUrl, String issuer, String clientId, Supplier<Secret> clientSecret,
        String subjectClaim, String requiredRole, String roleClaim, boolean allowWithoutRole) {

    public static final String DEFAULT_ROLE_CLAIM = "realm_access.roles";
    public static final String BASE_PATH = "/accounts";

    public WebConfig {
        Objects.requireNonNull(publicBaseUrl, "publicBaseUrl");
        Objects.requireNonNull(issuer, "issuer");
        Objects.requireNonNull(clientId, "clientId");
        Objects.requireNonNull(clientSecret, "clientSecret");
        Objects.requireNonNull(subjectClaim, "subjectClaim");
        if (!"https".equals(publicBaseUrl.getScheme()) || publicBaseUrl.getHost() == null
                || publicBaseUrl.getRawUserInfo() != null || publicBaseUrl.getRawQuery() != null
                || publicBaseUrl.getRawFragment() != null
                || (publicBaseUrl.getRawPath() != null && !publicBaseUrl.getRawPath().isEmpty()
                        && !"/".equals(publicBaseUrl.getRawPath()))) {
            throw new IllegalArgumentException("public.baseUrl must be an https URL without path, query or fragment");
        }
        if (clientId.isBlank() || subjectClaim.isBlank() || issuer.isBlank()) {
            throw new IllegalArgumentException("oauth.issuer, oauth.webClientId and oauth.subjectClaim must not be blank");
        }
        if (requiredRole != null && requiredRole.isBlank()) {
            throw new IllegalArgumentException("oauth.requiredRole must not be blank");
        }
        if (requiredRole == null && !allowWithoutRole) {
            throw new IllegalArgumentException("oauth.requiredRole is required unless oauth.allowWithoutRole is true");
        }
        roleClaim = roleClaim == null || roleClaim.isBlank() ? DEFAULT_ROLE_CLAIM : roleClaim;
    }

    /** {@code https://host[:port]} as browsers send it in {@code Origin}. */
    public String origin() {
        return "https://" + publicBaseUrl.getRawAuthority().toLowerCase(Locale.ROOT);
    }

    public String authority() {
        return publicBaseUrl.getRawAuthority().toLowerCase(Locale.ROOT);
    }

    public String redirectUri() {
        return origin() + BASE_PATH + "/callback";
    }

    @Override
    public String toString() {
        return "WebConfig[" + origin() + "]";
    }
}
