package de.alexanderlindhorst.imapmcp.core.linking;

import de.alexanderlindhorst.imapmcp.core.account.AccountRepository;
import de.alexanderlindhorst.imapmcp.core.account.MailAccount;
import de.alexanderlindhorst.imapmcp.core.account.ResolvedAccount;
import de.alexanderlindhorst.imapmcp.core.account.SecretRef;
import de.alexanderlindhorst.imapmcp.core.audit.AuditLog;
import de.alexanderlindhorst.imapmcp.core.audit.AuditWriteException;
import de.alexanderlindhorst.imapmcp.core.audit.EventInfo;
import de.alexanderlindhorst.imapmcp.core.imap.LoginProbe;
import de.alexanderlindhorst.imapmcp.core.imap.Xoauth2LoginProbe;
import de.alexanderlindhorst.imapmcp.core.net.HostRejectedException;
import de.alexanderlindhorst.imapmcp.core.net.SsrfPolicy;
import de.alexanderlindhorst.imapmcp.core.tenancy.AccountId;
import de.alexanderlindhorst.imapmcp.core.tenancy.TenantId;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * The upstream OAuth linking flow (SPEC 5.3, v0.29). {@code start} checks the account limit, the probe budget and the
 * start limit, writes the pending row (tenant of the web session, PKCE verifier, draft) and builds the authorization
 * URL with {@code login_hint}; the address the user entered is only a hint. {@code callback} consumes the state
 * first, checks the binding, exchanges the code, probes with XOAUTH2 as the entered (re-link: stored) address through
 * the SSRF checks and the probe budget, and only then stores account, grant and refresh token in the store's one
 * transaction. Refusals write an audit row (best effort; a failing row never changes the answer); nothing is stored
 * on any failure. No code, verifier, token, {@code error_description}, address or host reaches a log or an audit row.
 */
public final class UpstreamLinking implements UpstreamLinker {

    private static final int MAX_ADDRESS = 254;
    private static final String OFFLINE_ACCESS = "offline_access";
    private static final String LOCAL_CHARS = "!#$%&'*+-/=?^_`{|}~.";

    private final AccountRepository accounts;
    private final UpstreamProviders providers;
    private final UpstreamPendingStore pending;
    private final UpstreamAccountStore store;
    private final UpstreamCodeExchange exchange;
    private final Xoauth2LoginProbe probe;
    private final SsrfPolicy policy;
    private final AuditLog audit;
    private final ProbeRateLimiter probeLimiter;
    private final ProbeRateLimiter startLimiter;
    private final int maxAccounts;
    private final SecureRandom random = new SecureRandom();

    public UpstreamLinking(AccountRepository accounts, UpstreamProviders providers, UpstreamPendingStore pending,
            UpstreamAccountStore store, UpstreamCodeExchange exchange, Xoauth2LoginProbe probe, SsrfPolicy policy,
            AuditLog audit, ProbeRateLimiter probeLimiter, ProbeRateLimiter startLimiter, int maxAccounts) {
        this.accounts = Objects.requireNonNull(accounts, "accounts");
        this.providers = Objects.requireNonNull(providers, "providers");
        this.pending = Objects.requireNonNull(pending, "pending");
        this.store = Objects.requireNonNull(store, "store");
        this.exchange = Objects.requireNonNull(exchange, "exchange");
        this.probe = Objects.requireNonNull(probe, "probe");
        this.policy = Objects.requireNonNull(policy, "policy");
        this.audit = Objects.requireNonNull(audit, "audit");
        this.probeLimiter = Objects.requireNonNull(probeLimiter, "probeLimiter");
        this.startLimiter = Objects.requireNonNull(startLimiter, "startLimiter");
        if (maxAccounts < 1) {
            throw new IllegalArgumentException("maxAccounts must be positive");
        }
        this.maxAccounts = maxAccounts;
    }

    @Override
    public List<UpstreamProviderInfo> providers() {
        return providers.all();
    }

    @Override
    public Optional<UpstreamProviderInfo> provider(String id) {
        return id == null ? Optional.empty() : providers.find(id);
    }

    // ---- start ----

    @Override
    public Started start(TenantId tenant, String providerId, String label, String address, boolean readOnly,
            String relinkHandle, String requestId) {
        Optional<UpstreamProviderInfo> found = provider(providerId);
        if (found.isEmpty()) {
            return refused(UpstreamOutcome.NOT_FOUND);
        }
        UpstreamProviderInfo provider = found.get();
        AccountId relinkAccount = null;
        String draftLabel;
        String draftAddress;
        boolean draftReadOnly;
        if (relinkHandle != null) {
            // the tenant-scoped lookup is the only way to name an account; the provider is the account's
            Optional<ResolvedAccount> account = accounts.findByTenantAndHandle(tenant, relinkHandle)
                    .filter(a -> isUpstreamAccountOf(a.account(), provider.id()));
            if (account.isEmpty()) {
                return refused(UpstreamOutcome.NOT_FOUND);
            }
            MailAccount a = account.get().account();
            relinkAccount = a.id();
            draftLabel = a.label();
            draftAddress = addressOf(a);
            draftReadOnly = a.readOnly();
        } else {
            draftLabel = AccountLinking.label(label);
            draftAddress = address(address);
            draftReadOnly = readOnly;
            if (draftLabel == null || draftAddress == null) {
                return refused(UpstreamOutcome.INVALID_INPUT);
            }
        }
        String type = relinkAccount == null ? "link" : "relink";
        if (relinkAccount == null && accounts.findByTenant(tenant).size() >= maxAccounts) {
            return refuse(type, tenant, relinkAccount, requestId, UpstreamOutcome.ACCOUNT_LIMIT, "refused_limit");
        }
        if (!probeLimiter.hasRoom(tenant)) { // looks only: the probe itself takes the slot at the callback
            return refuse(type, tenant, relinkAccount, requestId, UpstreamOutcome.RATE_LIMITED,
                    "refused_rate_limit");
        }
        if (!startLimiter.tryAcquire(tenant)) {
            return refuse(type, tenant, relinkAccount, requestId, UpstreamOutcome.START_LIMIT,
                    "refused_start_limit");
        }
        String verifier = randomToken();
        String state;
        try {
            state = pending.begin(new UpstreamPendingStore.Pending(tenant, provider.id(), verifier, draftLabel,
                    draftAddress, draftReadOnly, relinkAccount));
        } catch (UpstreamPendingStore.TenantLimitReachedException e) {
            return refuse(type, tenant, relinkAccount, requestId, UpstreamOutcome.PENDING_LIMIT,
                    "refused_pending_limit");
        } catch (RuntimeException e) { // the table cap or the database: nothing was written
            return refuse(type, tenant, relinkAccount, requestId, UpstreamOutcome.UNAVAILABLE, "unavailable");
        }
        return new Started(UpstreamOutcome.OK, authorizationUrl(provider, state, verifier, draftAddress), state);
    }

    private static String authorizationUrl(UpstreamProviderInfo p, String state, String verifier, String address) {
        StringBuilder url = new StringBuilder(p.authorizationEndpoint().toString())
                .append(p.authorizationEndpoint().getRawQuery() == null ? "?" : "&");
        url.append("response_type=code&client_id=").append(enc(p.clientId()))
                .append("&redirect_uri=").append(enc(p.redirectUri()))
                .append("&scope=").append(enc(String.join(" ", p.scopes())))
                .append("&state=").append(enc(state))
                .append("&code_challenge=").append(enc(challenge(verifier))).append("&code_challenge_method=S256")
                .append("&login_hint=").append(enc(address));
        if (p.offlineConsent()) {
            url.append("&access_type=offline&prompt=consent");
        }
        return url.toString();
    }

    // ---- callback ----

    @Override
    public Completed callback(String state, String cookie, String pathProviderId, String code, String error) {
        if (state == null || state.isEmpty()) {
            return done(UpstreamOutcome.EXPIRED, false);
        }
        UpstreamPendingStore.Pending row;
        try {
            Optional<UpstreamPendingStore.Pending> consumed = pending.consume(state); // burned first, whatever follows
            if (consumed.isEmpty()) {
                return done(UpstreamOutcome.EXPIRED, false);
            }
            row = consumed.get();
        } catch (RuntimeException e) {
            return done(UpstreamOutcome.UNAVAILABLE, false);
        }
        if (cookie == null || pathProviderId == null || !MessageDigest.isEqual(
                cookie.getBytes(StandardCharsets.UTF_8), state.getBytes(StandardCharsets.UTF_8))
                || !row.providerId().equals(pathProviderId)) {
            return done(UpstreamOutcome.EXPIRED, false);
        }
        boolean relink = row.relinkAccount() != null;
        TenantId tenant = row.tenant(); // from the row the session's start wrote, never from the request
        String type = relink ? "relink" : "link";
        Optional<UpstreamProviderInfo> found = provider(row.providerId());
        if (found.isEmpty()) {
            return outcome(type, row, UpstreamOutcome.PROVIDER_ERROR, "provider_error");
        }
        UpstreamProviderInfo provider = found.get();
        MailAccount existing = null;
        if (relink) {
            existing = accounts.findByTenantAndId(tenant, row.relinkAccount())
                    .map(ResolvedAccount::account).filter(a -> isUpstreamAccountOf(a, provider.id())).orElse(null);
            if (existing == null) { // unlinked meanwhile: nothing to renew
                return done(UpstreamOutcome.EXPIRED, true);
            }
        }
        if (error != null) {
            return "access_denied".equals(error)
                    ? outcome(type, row, UpstreamOutcome.DENIED, "denied")
                    : outcome(type, row, UpstreamOutcome.PROVIDER_ERROR, "provider_error");
        }
        if (code == null || code.isEmpty()) {
            return outcome(type, row, UpstreamOutcome.PROVIDER_ERROR, "provider_error");
        }
        UpstreamCodeExchange.Result result;
        try {
            result = exchange.exchange(provider, code.toCharArray(), row.pkceVerifier().toCharArray());
        } catch (RuntimeException e) {
            result = new UpstreamCodeExchange.Result.Unavailable();
        }
        return switch (result) {
            case UpstreamCodeExchange.Result.Unavailable u ->
                    outcome(type, row, UpstreamOutcome.PROVIDER_UNAVAILABLE, "exchange_failed");
            case UpstreamCodeExchange.Result.InvalidGrant g ->
                    outcome(type, row, UpstreamOutcome.INCOMPLETE, "exchange_failed");
            case UpstreamCodeExchange.Result.Granted g -> {
                try {
                    yield granted(row, provider, existing, g);
                } finally {
                    g.close();
                }
            }
        };
    }

    private Completed granted(UpstreamPendingStore.Pending row, UpstreamProviderInfo provider, MailAccount existing,
            UpstreamCodeExchange.Result.Granted g) {
        boolean relink = existing != null;
        String type = relink ? "relink" : "link";
        if (g.refreshToken() == null || g.refreshToken().chars().length == 0 || !scopeComplete(provider, g.scope())) {
            return outcome(type, row, UpstreamOutcome.INCOMPLETE, "exchange_failed");
        }
        String host = relink ? existing.host() : provider.imapHost();
        int port = relink ? existing.port() : provider.imapPort();
        MailAccount.Security security = relink ? existing.security() : provider.imapSecurity();
        MailAccount.Flavor flavor = relink ? existing.flavor() : provider.flavor();
        try { // the policy may have changed since the account was linked
            host = policy.checkEndpoint(host, port, security);
        } catch (HostRejectedException e) {
            return outcome(type, row, UpstreamOutcome.PROBE_FAILED, "probe_failed");
        }
        if (!probeLimiter.tryAcquire(row.tenant())) {
            return outcome(type, row, UpstreamOutcome.PROBE_FAILED, "probe_failed");
        }
        MailAccount probeAccount = new MailAccount(new AccountId("probe"), row.label(), row.address(), host, port,
                security, flavor, row.address(), new SecretRef("db:"), true, false, MailAccount.Auth.XOAUTH2,
                MailAccount.Status.ACTIVE, 0L, provider.id());
        if (probe.probeXoauth2(probeAccount, g.accessToken().chars()) != LoginProbe.Result.OK) {
            return outcome(type, row, UpstreamOutcome.PROBE_FAILED, "probe_failed");
        }
        String scope = g.scope() == null || g.scope().isBlank() ? String.join(" ", provider.scopes())
                : g.scope().strip();
        String requestId = UUID.randomUUID().toString();
        try {
            if (relink) {
                return store.relink(row.tenant(), existing.id(), g.refreshToken().chars(), scope, requestId)
                        ? done(UpstreamOutcome.OK, true) : done(UpstreamOutcome.EXPIRED, true);
            }
            store.create(row.tenant(), new UpstreamAccountStore.NewUpstreamAccount(row.label(), row.address(),
                    provider.id(), host, port, security, flavor, row.readOnly(), scope), g.refreshToken().chars(),
                    maxAccounts, requestId);
            return done(UpstreamOutcome.OK, false);
        } catch (UpstreamAccountStore.DuplicateException e) {
            return outcome(type, row, UpstreamOutcome.DUPLICATE, "duplicate");
        } catch (LinkedAccountStore.LimitReachedException e) {
            return outcome(type, row, UpstreamOutcome.LIMIT, "limit");
        } catch (RuntimeException e) { // the store's unavailability, or anything unexpected: nothing was stored
            return outcome(type, row, UpstreamOutcome.UNAVAILABLE, "unavailable");
        }
    }

    /**
     * Every configured scope except {@code offline_access} (a request for a refresh token, not an access scope some
     * providers echo) must be among the granted ones; a provider that does not say gave what was requested (RFC 6749,
     * 5.1).
     */
    private static boolean scopeComplete(UpstreamProviderInfo provider, String granted) {
        if (granted == null || granted.isBlank()) {
            return true;
        }
        Set<String> have = new HashSet<>(List.of(granted.strip().split("\\s+")));
        return provider.scopes().stream().filter(s -> !OFFLINE_ACCESS.equals(s)).allMatch(have::contains);
    }

    // ---- helpers ----

    private static boolean isUpstreamAccountOf(MailAccount a, String providerId) {
        return a.auth() == MailAccount.Auth.XOAUTH2 && providerId.equals(a.providerId());
    }

    private static String addressOf(MailAccount a) {
        return a.address() != null ? a.address() : a.username();
    }

    private static Started refused(UpstreamOutcome outcome) {
        return new Started(outcome, null, null);
    }

    private Started refuse(String type, TenantId tenant, AccountId account, String requestId,
            UpstreamOutcome outcome, String result) {
        writeAudit(type, tenant, account, requestId, result);
        return refused(outcome);
    }

    private Completed outcome(String type, UpstreamPendingStore.Pending row, UpstreamOutcome outcome,
            String result) {
        writeAudit(type, row.tenant(), row.relinkAccount(), UUID.randomUUID().toString(), result);
        return done(outcome, row.relinkAccount() != null);
    }

    private static Completed done(UpstreamOutcome outcome, boolean relink) {
        return new Completed(outcome, relink);
    }

    /** Best effort: a failing refusal row is the audit log's to report and never changes the answer. */
    private void writeAudit(String type, TenantId tenant, AccountId account, String requestId, String result) {
        try {
            audit.event(new EventInfo(type, tenant, account, requestId, result));
        } catch (AuditWriteException ignored) {
            // the original answer stands
        }
    }

    /**
     * The address as a login hint: ASCII, one {@code @}, a dot-and-letter domain; anything else is refused. The
     * probe, not this check, decides whether the mailbox exists.
     */
    static String address(String raw) {
        if (raw == null) {
            return null;
        }
        String s = raw.strip();
        int at = s.indexOf('@');
        if (s.length() < 3 || s.length() > MAX_ADDRESS || at < 1 || at != s.lastIndexOf('@') || at == s.length() - 1) {
            return null;
        }
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            boolean ok = i < at
                    ? (c < 128 && (Character.isLetterOrDigit(c) || LOCAL_CHARS.indexOf(c) >= 0))
                    : i == at || (c < 128 && (Character.isLetterOrDigit(c) || c == '.' || c == '-'));
            if (!ok) {
                return null;
            }
        }
        return s;
    }

    private String randomToken() {
        byte[] b = new byte[32];
        random.nextBytes(b);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(b);
    }

    private static String challenge(String verifier) {
        try {
            return Base64.getUrlEncoder().withoutPadding().encodeToString(
                    MessageDigest.getInstance("SHA-256").digest(verifier.getBytes(StandardCharsets.US_ASCII)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String enc(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8);
    }
}
