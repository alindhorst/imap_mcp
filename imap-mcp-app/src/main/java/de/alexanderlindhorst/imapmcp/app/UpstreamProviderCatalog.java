package de.alexanderlindhorst.imapmcp.app;

import de.alexanderlindhorst.imapmcp.app.config.UpstreamProvider;
import de.alexanderlindhorst.imapmcp.core.linking.UpstreamProviderInfo;
import de.alexanderlindhorst.imapmcp.core.linking.UpstreamProviders;
import java.util.List;
import java.util.Optional;

/** The configured upstream providers (SPEC 5.7) as the linking flow sees them: no secret, no token endpoint. */
public final class UpstreamProviderCatalog implements UpstreamProviders {

    public UpstreamProviderCatalog(List<UpstreamProvider> providers) {
    }

    @Override
    public List<UpstreamProviderInfo> all() {
        throw new UnsupportedOperationException("not yet");
    }

    @Override
    public Optional<UpstreamProviderInfo> find(String id) {
        throw new UnsupportedOperationException("not yet");
    }
}
