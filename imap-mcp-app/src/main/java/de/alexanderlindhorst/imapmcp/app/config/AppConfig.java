package de.alexanderlindhorst.imapmcp.app.config;

import de.alexanderlindhorst.imapmcp.core.account.MailAccount;
import de.alexanderlindhorst.imapmcp.core.account.SecretRef;
import de.alexanderlindhorst.imapmcp.core.tenancy.AccountId;
import java.time.Duration;
import java.util.List;

/**
 * Validated application configuration (SPEC 5.7). Built by {@link ConfigLoader}; all values are already
 * range-checked. {@code mode} and {@code logLevel} come only from the named environment overrides and are
 * null when unset. Secret references are references, never secrets.
 */
public record AppConfig(List<Account> accounts, Imap imap, Pool pool, Tools tools, Secrets secrets, Http http,
        Security security, String mode, String logLevel) {

    public record Account(String id, String label, String address, String host, int port,
            MailAccount.Security security, MailAccount.Flavor flavor, String username, SecretRef password) {

        public MailAccount toMailAccount() {
            return new MailAccount(new AccountId(id), label, address, host, port, security, flavor, username,
                    password);
        }

        @Override
        public String toString() {
            return "Account[id=" + id + ", host=" + host + ", port=" + port + ", security=" + security
                    + ", flavor=" + flavor + "]";
        }
    }

    public record Imap(Duration connectTimeout, Duration readTimeout, Duration writeTimeout, boolean trace) {
    }

    public record Pool(int maxPerMailbox, int maxPerTenant, int maxTotal, Duration idleEviction,
            Duration validateAfterIdle, Duration maxLifetime, Duration acquireTimeout, Duration capabilityTtl) {
    }

    public record Tools(Duration deadline, int maxCharsCap, long maxBodyPartBytes, int maxFolders, int sinceMaxCandidates) {
    }

    public record Secrets(Duration keyringTimeout) {
    }

    public record Http(String bind, int port, boolean allowNonLoopbackBind, SecretRef token,
            List<String> allowedHosts, List<String> allowedOrigins, long maxRequestBytes) {
    }

    public record Security(SecretRef handleKey) {
    }
}
