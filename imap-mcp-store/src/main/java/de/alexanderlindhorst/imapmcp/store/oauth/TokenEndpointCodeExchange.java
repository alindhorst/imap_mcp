package de.alexanderlindhorst.imapmcp.store.oauth;

import de.alexanderlindhorst.imapmcp.core.linking.UpstreamCodeExchange;
import de.alexanderlindhorst.imapmcp.core.linking.UpstreamProviderInfo;

/** {@link UpstreamCodeExchange} on the token client (SPEC 5.3 "Upstream token egress"). */
public final class TokenEndpointCodeExchange implements UpstreamCodeExchange {

    public TokenEndpointCodeExchange(TokenEndpoint client, TokenProviders providers) {
    }

    @Override
    public Result exchange(UpstreamProviderInfo provider, char[] code, char[] codeVerifier) {
        throw new UnsupportedOperationException("not yet");
    }
}
