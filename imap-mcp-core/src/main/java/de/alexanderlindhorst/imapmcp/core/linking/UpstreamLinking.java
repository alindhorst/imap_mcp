package de.alexanderlindhorst.imapmcp.core.linking;

import de.alexanderlindhorst.imapmcp.core.account.AccountRepository;
import de.alexanderlindhorst.imapmcp.core.audit.AuditLog;
import de.alexanderlindhorst.imapmcp.core.imap.Xoauth2LoginProbe;
import de.alexanderlindhorst.imapmcp.core.net.SsrfPolicy;
import de.alexanderlindhorst.imapmcp.core.tenancy.TenantId;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** The upstream OAuth linking flow (SPEC 5.3); see {@link UpstreamLinker}. */
public final class UpstreamLinking implements UpstreamLinker {

    public UpstreamLinking(AccountRepository accounts, UpstreamProviders providers, UpstreamPendingStore pending,
            UpstreamAccountStore store, UpstreamCodeExchange exchange, Xoauth2LoginProbe probe, SsrfPolicy policy,
            AuditLog audit, ProbeRateLimiter probeLimiter, ProbeRateLimiter startLimiter, int maxAccounts) {
        Objects.requireNonNull(accounts);
    }

    @Override
    public List<UpstreamProviderInfo> providers() {
        throw new UnsupportedOperationException("not yet");
    }

    @Override
    public Optional<UpstreamProviderInfo> provider(String id) {
        throw new UnsupportedOperationException("not yet");
    }

    @Override
    public Started start(TenantId tenant, String providerId, String label, String address, boolean readOnly,
            String relinkHandle, String requestId) {
        throw new UnsupportedOperationException("not yet");
    }

    @Override
    public Completed callback(String state, String cookie, String pathProviderId, String code, String error) {
        throw new UnsupportedOperationException("not yet");
    }
}
