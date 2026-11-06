package de.alexanderlindhorst.imapmcp.core.linking;

import de.alexanderlindhorst.imapmcp.core.account.AccountRepository;
import de.alexanderlindhorst.imapmcp.core.account.MailAccount;
import de.alexanderlindhorst.imapmcp.core.account.ResolvedAccount;
import de.alexanderlindhorst.imapmcp.core.account.SecretRef;
import de.alexanderlindhorst.imapmcp.core.audit.AuditLog;
import de.alexanderlindhorst.imapmcp.core.audit.AuditWriteException;
import de.alexanderlindhorst.imapmcp.core.audit.EventInfo;
import de.alexanderlindhorst.imapmcp.core.imap.LoginProbe;
import de.alexanderlindhorst.imapmcp.core.net.HostRejectedException;
import de.alexanderlindhorst.imapmcp.core.net.SsrfPolicy;
import de.alexanderlindhorst.imapmcp.core.tenancy.AccountId;
import de.alexanderlindhorst.imapmcp.core.tenancy.TenantId;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * The linking actions of {@code http-public} (SPEC 5.3): link, re-link, settings and unlink for the tenant of the
 * web session. A link or re-link applies the SSRF policy, takes one of the tenant's 10 probes per hour, probes the
 * server with the password (connect, TLS, LOGIN, CAPABILITY, LOGOUT, through the pinned socket factory) and only
 * then stores anything. Refusals write an audit row (best effort: a failing refusal row never changes the
 * answer); successful actions are audited in the store's own transaction. Neither the password nor host, user name
 * or address reaches a log or an audit row.
 */
public final class AccountLinking implements AccountLinker {

    /** {@code allow_purge} needs a login at most this old. */
    public static final Duration FRESH_LOGIN = Duration.ofMinutes(5);
    private static final int MAX_LABEL = 80;
    private static final int MAX_USERNAME = 255;
    private static final int MAX_PASSWORD = 1024;

    private final AccountRepository accounts;
    private final LinkedAccountStore store;
    private final LoginProbe probe;
    private final SsrfPolicy policy;
    private final AuditLog audit;
    private final ProbeRateLimiter limiter;
    private final Clock clock;
    private final int maxAccounts;
    private final boolean purgeEnabled;

    public AccountLinking(AccountRepository accounts, LinkedAccountStore store, LoginProbe probe, SsrfPolicy policy,
            AuditLog audit, ProbeRateLimiter limiter, Clock clock, int maxAccounts, boolean purgeEnabled) {
        this.accounts = Objects.requireNonNull(accounts, "accounts");
        this.store = Objects.requireNonNull(store, "store");
        this.probe = Objects.requireNonNull(probe, "probe");
        this.policy = Objects.requireNonNull(policy, "policy");
        this.audit = Objects.requireNonNull(audit, "audit");
        this.limiter = Objects.requireNonNull(limiter, "limiter");
        this.clock = Objects.requireNonNull(clock, "clock");
        if (maxAccounts < 1) {
            throw new IllegalArgumentException("maxAccounts must be positive");
        }
        this.maxAccounts = maxAccounts;
        this.purgeEnabled = purgeEnabled;
    }

    @Override
    public boolean purgeEnabled() {
        return purgeEnabled;
    }

    @Override
    public int maxAccounts() {
        return maxAccounts;
    }

    @Override
    public List<ResolvedAccount> list(TenantId tenant) {
        return accounts.findByTenant(tenant);
    }

    @Override
    public Optional<ResolvedAccount> find(TenantId tenant, String handle) {
        return handle == null ? Optional.empty() : accounts.findByTenantAndHandle(tenant, handle);
    }

    // ---- link ----

    @Override
    public LinkOutcome link(TenantId tenant, LinkRequest request, char[] password, Instant authTime,
            String requestId) {
        String label = label(request.label());
        String username = username(request.username());
        if (label == null || username == null || !validPassword(password) || request.security() == null
                || request.host() == null || request.host().isBlank()
                || request.port() < 1 || request.port() > 65535 || (request.readOnly() && request.allowPurge())) {
            return refuse("link", tenant, null, requestId, LinkOutcome.INVALID_INPUT, "invalid_input");
        }
        LinkOutcome purge = purgeGate(request.allowPurge(), authTime);
        if (purge != LinkOutcome.OK) {
            return refuse("link", tenant, null, requestId, purge, purge == LinkOutcome.PURGE_DISABLED
                    ? "refused_purge_disabled" : "refused_reauth");
        }
        String host;
        try {
            host = policy.checkEndpoint(request.host(), request.port(), request.security());
        } catch (HostRejectedException e) {
            return refuse("link", tenant, null, requestId, LinkOutcome.HOST_REFUSED, "refused_host");
        }
        if (accounts.findByTenant(tenant).size() >= maxAccounts) {
            return refuse("link", tenant, null, requestId, LinkOutcome.ACCOUNT_LIMIT, "refused_limit");
        }
        if (!limiter.tryAcquire(tenant)) {
            return refuse("link", tenant, null, requestId, LinkOutcome.RATE_LIMITED, "refused_rate_limit");
        }
        LinkOutcome probed = probe(host, request.port(), request.security(), username, password);
        if (probed != LinkOutcome.OK) {
            return refuse("link", tenant, null, requestId, probed, probeResult(probed));
        }
        try {
            store.create(tenant, new LinkRequest(label, host, request.port(), request.security(), username,
                    request.readOnly(), request.allowPurge()), password, maxAccounts, requestId);
            return LinkOutcome.OK;
        } catch (LinkedAccountStore.LimitReachedException e) {
            return refuse("link", tenant, null, requestId, LinkOutcome.ACCOUNT_LIMIT, "refused_limit");
        } catch (LinkedAccountStore.UnavailableException e) {
            return refuse("link", tenant, null, requestId, LinkOutcome.UNAVAILABLE, "unavailable");
        }
    }

    // ---- re-link ----

    @Override
    public LinkOutcome relink(TenantId tenant, String handle, char[] password, String requestId) {
        Optional<ResolvedAccount> account = find(tenant, handle);
        if (account.isEmpty()) {
            return LinkOutcome.NOT_FOUND;
        }
        AccountId id = account.get().account().id();
        if (!validPassword(password)) {
            return refuse("relink", tenant, id, requestId, LinkOutcome.INVALID_INPUT, "invalid_input");
        }
        if (!limiter.tryAcquire(tenant)) {
            return refuse("relink", tenant, id, requestId, LinkOutcome.RATE_LIMITED, "refused_rate_limit");
        }
        MailAccount a = account.get().account();
        LinkOutcome probed = probe(a.host(), a.port(), a.security(), a.username(), password);
        if (probed != LinkOutcome.OK) {
            return refuse("relink", tenant, id, requestId, probed, probeResult(probed));
        }
        try {
            return store.relink(tenant, id, password, requestId) ? LinkOutcome.OK : LinkOutcome.NOT_FOUND;
        } catch (LinkedAccountStore.UnavailableException e) {
            return refuse("relink", tenant, id, requestId, LinkOutcome.UNAVAILABLE, "unavailable");
        }
    }

    // ---- settings ----

    @Override
    public LinkOutcome updateSettings(TenantId tenant, String handle, SettingsRequest request, Instant authTime,
            String requestId) {
        Optional<ResolvedAccount> account = find(tenant, handle);
        if (account.isEmpty()) {
            return LinkOutcome.NOT_FOUND;
        }
        MailAccount current = account.get().account();
        String label = label(request.label());
        if (label == null || (request.readOnly() && request.allowPurge())) {
            return refuse("settings", tenant, current.id(), requestId, LinkOutcome.INVALID_INPUT, "invalid_input");
        }
        if (request.allowPurge() && !current.allowPurge()) { // only turning it on needs the switch and a fresh login
            LinkOutcome purge = purgeGate(true, authTime);
            if (purge != LinkOutcome.OK) {
                return refuse("settings", tenant, current.id(), requestId, purge,
                        purge == LinkOutcome.PURGE_DISABLED ? "refused_purge_disabled" : "refused_reauth");
            }
        }
        try {
            return store.updateSettings(tenant, current.id(), label, request.readOnly(), request.allowPurge(),
                    requestId) ? LinkOutcome.OK : LinkOutcome.NOT_FOUND;
        } catch (LinkedAccountStore.UnavailableException e) {
            return refuse("settings", tenant, current.id(), requestId, LinkOutcome.UNAVAILABLE, "unavailable");
        }
    }

    // ---- unlink ----

    @Override
    public LinkOutcome unlink(TenantId tenant, String handle, String requestId) {
        Optional<ResolvedAccount> account = find(tenant, handle);
        if (account.isEmpty()) {
            return LinkOutcome.NOT_FOUND;
        }
        try {
            return store.unlink(tenant, account.get().account().id(), requestId) ? LinkOutcome.OK
                    : LinkOutcome.NOT_FOUND;
        } catch (LinkedAccountStore.UnavailableException e) {
            return refuse("unlink", tenant, account.get().account().id(), requestId, LinkOutcome.UNAVAILABLE,
                    "unavailable");
        }
    }

    // ---- helpers ----

    private LinkOutcome purgeGate(boolean allowPurge, Instant authTime) {
        if (!allowPurge) {
            return LinkOutcome.OK;
        }
        if (!purgeEnabled) {
            return LinkOutcome.PURGE_DISABLED;
        }
        return authTime != null && !authTime.isBefore(clock.instant().minus(FRESH_LOGIN))
                ? LinkOutcome.OK : LinkOutcome.REAUTH_REQUIRED;
    }

    private LinkOutcome probe(String host, int port, MailAccount.Security security, String username,
            char[] password) {
        MailAccount probeAccount = new MailAccount(new AccountId("probe"), "probe", null, host, port, security,
                MailAccount.Flavor.GENERIC, username, new SecretRef("db:"), true, false);
        return switch (probe.probe(probeAccount, password)) {
            case OK -> LinkOutcome.OK;
            case AUTH_FAILED -> LinkOutcome.PROBE_AUTH_FAILED;
            case UNREACHABLE -> LinkOutcome.PROBE_UNREACHABLE;
        };
    }

    private static String probeResult(LinkOutcome o) {
        return o == LinkOutcome.PROBE_AUTH_FAILED ? "refused_probe_auth" : "refused_probe_unreachable";
    }

    /** Writes the refusal row (a failure is logged by the audit log and ignored) and returns the outcome. */
    private LinkOutcome refuse(String type, TenantId tenant, AccountId account, String requestId, LinkOutcome outcome,
            String result) {
        try {
            audit.event(new EventInfo(type, tenant, account, requestId, result));
        } catch (AuditWriteException ignored) {
            // the original answer stands
        }
        return outcome;
    }

    private static boolean validPassword(char[] password) {
        if (password == null || password.length == 0 || password.length > MAX_PASSWORD) {
            return false;
        }
        for (char c : password) {
            if (c == '\0' || c == '\r' || c == '\n') {
                return false;
            }
        }
        return true;
    }

    /** Trimmed, 1 to 80 characters, no control or format characters (bidi and the like); null if unusable. */
    static String label(String raw) {
        return text(raw, MAX_LABEL);
    }

    private static String username(String raw) {
        return text(raw, MAX_USERNAME);
    }

    private static String text(String raw, int max) {
        if (raw == null) {
            return null;
        }
        String s = raw.strip();
        if (s.isEmpty() || s.length() > max) {
            return null;
        }
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (Character.isISOControl(c) || Character.getType(c) == Character.FORMAT
                    || Character.getType(c) == Character.LINE_SEPARATOR
                    || Character.getType(c) == Character.PARAGRAPH_SEPARATOR) {
                return null;
            }
        }
        return s;
    }
}
