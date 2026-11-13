package de.alexanderlindhorst.imapmcp.store.oauth;

import de.alexanderlindhorst.imapmcp.core.linking.UpstreamCodeExchange;
import de.alexanderlindhorst.imapmcp.core.linking.UpstreamProviderInfo;
import java.net.URI;
import java.util.Objects;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * {@link UpstreamCodeExchange} on the token client (SPEC 5.3 "Upstream token egress"): one authorization-code
 * exchange with the PKCE verifier and the derived redirect URI. {@code invalid_grant} and {@code invalid_scope} are
 * {@code InvalidGrant}; every other failure (timeout, 5xx, 429, a refused client, an unusable answer) is
 * {@code Unavailable}. Nothing of a response is logged; a rejected client is logged by provider id only.
 */
public final class TokenEndpointCodeExchange implements UpstreamCodeExchange {

    private static final Logger LOG = LoggerFactory.getLogger(TokenEndpointCodeExchange.class);

    private final TokenEndpoint client;
    private final TokenProviders providers;

    public TokenEndpointCodeExchange(TokenEndpoint client, TokenProviders providers) {
        this.client = Objects.requireNonNull(client, "client");
        this.providers = Objects.requireNonNull(providers, "providers");
    }

    @Override
    public Result exchange(UpstreamProviderInfo provider, char[] code, char[] codeVerifier) {
        Optional<TokenProvider> token = providers.find(provider.id());
        if (token.isEmpty()) {
            return new Result.Unavailable();
        }
        TokenResponse response;
        try {
            response = client.exchangeCode(token.get(), code, codeVerifier, URI.create(provider.redirectUri()));
        } catch (RuntimeException e) {
            LOG.warn("code exchange failed provider={} class={}", provider.id(), e.getClass().getSimpleName());
            return new Result.Unavailable();
        }
        return switch (response) {
            case TokenResponse.Granted g -> new Result.Granted(g.accessToken(), g.rotatedRefreshToken(), g.scope());
            case TokenResponse.InvalidGrant x -> new Result.InvalidGrant();
            case TokenResponse.InvalidClient x -> rejected(provider);
            case TokenResponse.UnauthorizedClient x -> rejected(provider);
            case TokenResponse.Unavailable x -> new Result.Unavailable();
            case TokenResponse.Malformed x -> new Result.Unavailable();
            case TokenResponse.Refused x -> new Result.Unavailable();
        };
    }

    private static Result rejected(UpstreamProviderInfo provider) {
        LOG.error("oauth client rejected by provider={}", provider.id());
        return new Result.Unavailable();
    }
}
