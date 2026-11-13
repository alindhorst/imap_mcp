package de.alexanderlindhorst.imapmcp.core.linking;

import java.util.List;
import java.util.Optional;

/** The configured upstream providers (SPEC 5.7), in configuration order. */
public interface UpstreamProviders {

    List<UpstreamProviderInfo> all();

    Optional<UpstreamProviderInfo> find(String id);
}
