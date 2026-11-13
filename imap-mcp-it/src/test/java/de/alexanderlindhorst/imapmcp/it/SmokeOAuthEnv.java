package de.alexanderlindhorst.imapmcp.it;

import de.alexanderlindhorst.imapmcp.it.SmokeEnv.Provider;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Configuration of the opt-in XOAUTH2 smoke harness (#328, SPEC 5.5, 5.8) from environment variables only. Per
 * provider ({@code <P>} is {@code GMAIL} or {@code M365}): {@code IMAP_MCP_SMOKE_<P>_USER} (the test address),
 * {@code IMAP_MCP_SMOKE_<P>_OAUTH_CLIENT_ID}, {@code ..._OAUTH_CLIENT_SECRET_REF} (an {@code env:} or {@code file:}
 * reference as in the server configuration, never the secret itself) and {@code ..._OAUTH_REFRESH_TOKEN}. Optional:
 * {@code ..._OAUTH_TENANT} (Microsoft), {@code ..._OAUTH_REVOKED_REFRESH_TOKEN} (a token the owner revoked; without
 * it a made-up token stands in), {@code IMAP_MCP_SMOKE_OAUTH_WRITE=1} (one reversible flag change) and
 * {@code IMAP_MCP_SMOKE_OAUTH_PURGE_SUBJECT} (purge from Trash the messages with exactly this subject). Nothing here
 * returns or prints a secret: reasons name variables only and {@link Settings#toString()} is redacted.
 */
public final class SmokeOAuthEnv {

    static final String WRITE_VARIABLE = "IMAP_MCP_SMOKE_OAUTH_WRITE";
    static final String PURGE_VARIABLE = "IMAP_MCP_SMOKE_OAUTH_PURGE_SUBJECT";

    private SmokeOAuthEnv() {
    }

    /** One test account; holds credentials, so it is never printed. */
    public record Settings(Provider provider, String user, String clientId, String clientSecretRef,
            String refreshToken, Optional<String> revokedRefreshToken, Optional<String> microsoftTenant,
            boolean writeEnabled, Optional<String> purgeSubject) {

        public String providerKind() {
            return provider == Provider.GMAIL ? "google" : "microsoft";
        }

        /** The {@code oauth.upstream.providers} entry (an indented block under {@code providers:}). */
        public String providerYaml() {
            StringBuilder b = new StringBuilder();
            b.append("        ").append(providerKind()).append(":\n");
            b.append("          kind: ").append(providerKind()).append('\n');
            b.append("          clientId: ").append(clientId).append('\n');
            b.append("          clientSecret: ").append(clientSecretRef).append('\n');
            microsoftTenant.ifPresent(t -> b.append("          tenant: ").append(t).append('\n'));
            return b.toString();
        }

        @Override
        public String toString() {
            return "Settings[" + provider + ", credentials redacted]";
        }
    }

    private static List<String> required(Provider p) {
        return List.of(p.variable("USER"), p.variable("OAUTH_CLIENT_ID"), p.variable("OAUTH_CLIENT_SECRET_REF"),
                p.variable("OAUTH_REFRESH_TOKEN"));
    }

    /** Empty when configured; otherwise the reason a test of this provider is skipped. */
    public static Optional<String> skipReason(Provider p, Map<String, String> env) {
        List<String> missing = new ArrayList<>();
        for (String v : required(p)) {
            if (!present(env, v)) {
                missing.add(v);
            }
        }
        if (missing.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of("XOAUTH2 smoke test skipped: set " + String.join(", ", required(p))
                + " to run it against a real " + p.title + " account");
    }

    public static Optional<Settings> settings(Provider p, Map<String, String> env) {
        if (skipReason(p, env).isPresent()) {
            return Optional.empty();
        }
        String ref = env.get(p.variable("OAUTH_CLIENT_SECRET_REF")).strip();
        if (!ref.startsWith("env:") && !ref.startsWith("file:")) {
            throw new IllegalArgumentException(p.variable("OAUTH_CLIENT_SECRET_REF")
                    + " must be an env: or file: reference, not the secret itself");
        }
        return Optional.of(new Settings(p, env.get(p.variable("USER")).strip(),
                env.get(p.variable("OAUTH_CLIENT_ID")).strip(), ref, env.get(p.variable("OAUTH_REFRESH_TOKEN")).strip(),
                optional(env, p.variable("OAUTH_REVOKED_REFRESH_TOKEN")),
                p == Provider.M365 ? optional(env, p.variable("OAUTH_TENANT")) : Optional.empty(),
                "1".equals(env.get(WRITE_VARIABLE)), optional(env, PURGE_VARIABLE)));
    }

    private static Optional<String> optional(Map<String, String> env, String name) {
        return present(env, name) ? Optional.of(env.get(name).strip()) : Optional.empty();
    }

    private static boolean present(Map<String, String> env, String name) {
        String v = env.get(name);
        return v != null && !v.isBlank();
    }
}
