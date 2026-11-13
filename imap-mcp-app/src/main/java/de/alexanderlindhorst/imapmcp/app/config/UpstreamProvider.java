package de.alexanderlindhorst.imapmcp.app.config;

import de.alexanderlindhorst.imapmcp.core.account.MailAccount.Security;
import de.alexanderlindhorst.imapmcp.core.account.SecretRef;
import java.net.URI;
import java.util.List;

/**
 * One validated {@code oauth.upstream.providers.<id>} entry (SPEC 5.7, v0.29; {@code http-public} only): the endpoints,
 * scopes and IMAP settings are the fixed values of {@code google} and {@code microsoft} or the configured ones of
 * {@code generic}. The client secret is a reference, resolved at use. Immutable.
 *
 * @param tenant Microsoft tenant, null for the other kinds
 * @param redirectUri derived from {@code public.baseUrl} and the id, never configured
 */
public record UpstreamProvider(String id, Kind kind, String clientId, String label, SecretRef clientSecret,
        String tenant, String authorizationEndpoint, String tokenEndpoint, List<String> scopes, String imapHost,
        int imapPort, Security imapSecurity, String redirectUri) {

    public enum Kind {
        GOOGLE, MICROSOFT, GENERIC
    }

    public UpstreamProvider {
        scopes = List.copyOf(scopes);
    }

    /** Host of the token endpoint (the only host the server calls with the refresh token, SPEC 5.3 egress). */
    public String tokenHost() {
        return URI.create(tokenEndpoint).getHost();
    }

    /** The Jakarta Mail protocol (and property prefix): {@code gimaps} for google, else {@code imaps} or {@code imap}. */
    public String protocol() {
        if (kind == Kind.GOOGLE) {
            return "gimaps";
        }
        return imapSecurity == Security.STARTTLS ? "imap" : "imaps";
    }

    /** The client id is not secret but is kept out of logs; the secret reference hides itself. */
    @Override
    public String toString() {
        return "UpstreamProvider[id=" + id + ", kind=" + kind + "]";
    }
}
