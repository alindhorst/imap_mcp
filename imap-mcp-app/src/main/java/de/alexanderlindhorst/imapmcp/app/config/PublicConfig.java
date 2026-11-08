package de.alexanderlindhorst.imapmcp.app.config;

import de.alexanderlindhorst.imapmcp.core.account.SecretRef;
import java.time.Duration;
import java.util.List;

/**
 * Validated configuration of {@code http-public} (SPEC 5.7). There are no configured accounts and no static token:
 * accounts and credentials live in the database. Built by {@link PublicConfigLoader}; values are range-checked, secret
 * values are references only.
 */
public record PublicConfig(AppConfig.Imap imap, AppConfig.Pool pool, AppConfig.Secrets secrets, Tools tools,
        Http http, Db db, Oauth oauth, Public pub, Ssrf ssrf, Kms kms, boolean purgeEnabled,
        AppConfig.Security security, String logLevel) {

    /** Same configuration on another port (tests bind port 0). */
    public PublicConfig withPort(int port) {
        return new PublicConfig(imap, pool, secrets, tools,
                new Http(http.bind(), port, http.allowNonLoopbackBind(), http.allowedHosts(), http.allowedOrigins(),
                        http.maxRequestBytes(), http.stateful(), http.initializeRatePerMinute(), http.initializeRateSet()),
                db, oauth, pub, ssrf, kms, purgeEnabled, security, logLevel);
    }

    /** {@code tools.*} of http-public: no download directory; the embedded download has its own cap and limiter. */
    public record Tools(Duration deadline, int maxCharsCap, long maxBodyPartBytes, int maxFolders,
            int sinceMaxCandidates, long inlineMaxBytes, int inlineConcurrency, int inlineConcurrencyPerTenant) {
    }

    /**
     * {@code http.*}: no token (the static token does not exist here); {@code stateful} is the D8 transport switch;
     * {@code initializeRatePerMinute} (D12, 1 to 600, default 10) matters only when stateful, and
     * {@code initializeRateSet} tells whether it was configured (for the startup WARN).
     */
    public record Http(String bind, int port, boolean allowNonLoopbackBind, List<String> allowedHosts,
            List<String> allowedOrigins, long maxRequestBytes, boolean stateful, int initializeRatePerMinute,
            boolean initializeRateSet) {
    }

    public record Db(String url, String username, SecretRef password, int maxPoolSize) {
    }

    /** {@code oauth.*}; {@code requiredRole} null only together with {@code allowWithoutRole}. */
    public record Oauth(String issuer, String resource, String subjectClaim, String requiredRole, String roleClaim,
            boolean allowWithoutRole, String webClientId, SecretRef webClientSecret) {
    }

    /** {@code public.*}: {@code baseUrl} is the https origin (and path prefix) the service is reached at. */
    public record Public(String baseUrl, int maxAccountsPerTenant) {
    }

    public record Ssrf(List<String> allowedHosts, boolean allowAnyPublicHost, List<String> extraBlockedCidrs) {
    }

    /** {@code kms.*} (D6): {@code local} (keks) or {@code vault} (Transit). */
    public record Kms(String provider, String currentKekId, List<Kek> keks,
            de.alexanderlindhorst.imapmcp.store.crypto.VaultConfig vault) {
    }

    public record Kek(String id, SecretRef key) {
    }
}
