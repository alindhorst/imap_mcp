package de.alexanderlindhorst.imapmcp.app;

import de.alexanderlindhorst.imapmcp.app.config.UpstreamProvider;
import de.alexanderlindhorst.imapmcp.core.linking.UpstreamProviderInfo;
import de.alexanderlindhorst.imapmcp.core.linking.UpstreamProviders;
import de.alexanderlindhorst.imapmcp.core.account.MailAccount;
import java.net.URI;
import java.util.List;
import java.util.Optional;

/** The configured upstream providers (SPEC 5.7) as the linking flow sees them: no secret, no token endpoint. */
public final class UpstreamProviderCatalog implements UpstreamProviders {

    private final List<UpstreamProviderInfo> infos;

    public UpstreamProviderCatalog(List<UpstreamProvider> providers) {
        this.infos = providers.stream().map(UpstreamProviderCatalog::info).toList();
    }

    private static UpstreamProviderInfo info(UpstreamProvider p) {
        boolean google = p.kind() == UpstreamProvider.Kind.GOOGLE;
        return new UpstreamProviderInfo(p.id(), p.label(), URI.create(p.authorizationEndpoint()), p.clientId(),
                p.scopes(), p.redirectUri(), p.imapHost(), p.imapPort(), p.imapSecurity(),
                google ? MailAccount.Flavor.GMAIL : MailAccount.Flavor.GENERIC, google);
    }

    @Override
    public List<UpstreamProviderInfo> all() {
        return infos;
    }

    @Override
    public Optional<UpstreamProviderInfo> find(String id) {
        return id == null ? Optional.empty() : infos.stream().filter(i -> i.id().equals(id)).findFirst();
    }
}
