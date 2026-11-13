package de.alexanderlindhorst.imapmcp.core.linking;

import de.alexanderlindhorst.imapmcp.core.account.MailAccount;
import java.net.URI;
import java.util.List;
import java.util.Objects;

/**
 * What the linking flow needs of a configured upstream OAuth provider (SPEC 5.7): the authorization endpoint, the
 * public client id, the requested scopes, the redirect URI and the fixed IMAP endpoint of the accounts it creates.
 * {@code offlineConsent} (Google) adds {@code access_type=offline} and {@code prompt=consent} to the authorization
 * request. No secret is part of it; the client secret stays with the token client.
 */
public record UpstreamProviderInfo(String id, String label, URI authorizationEndpoint, String clientId,
        List<String> scopes, String redirectUri, String imapHost, int imapPort, MailAccount.Security imapSecurity,
        MailAccount.Flavor flavor, boolean offlineConsent) {

    public UpstreamProviderInfo {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(label, "label");
        Objects.requireNonNull(authorizationEndpoint, "authorizationEndpoint");
        Objects.requireNonNull(clientId, "clientId");
        Objects.requireNonNull(redirectUri, "redirectUri");
        Objects.requireNonNull(imapHost, "imapHost");
        Objects.requireNonNull(imapSecurity, "imapSecurity");
        Objects.requireNonNull(flavor, "flavor");
        scopes = List.copyOf(scopes);
    }

    @Override
    public String toString() {
        return "UpstreamProviderInfo[" + id + "]";
    }
}
