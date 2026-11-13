package de.alexanderlindhorst.imapmcp.store.oauth;

import static org.assertj.core.api.Assertions.assertThat;

import de.alexanderlindhorst.imapmcp.core.account.MailAccount;
import de.alexanderlindhorst.imapmcp.core.account.Secret;
import de.alexanderlindhorst.imapmcp.core.linking.UpstreamCodeExchange;
import de.alexanderlindhorst.imapmcp.core.linking.UpstreamProviderInfo;
import java.net.URI;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class TokenEndpointCodeExchangeTest {

    private static final UpstreamProviderInfo INFO = new UpstreamProviderInfo("google", "Google",
            URI.create("https://accounts.example/auth"), "client-1", List.of("scope"),
            "https://mcp.example/accounts/oauth/google/callback", "imap.example", 993,
            MailAccount.Security.IMPLICIT_TLS, MailAccount.Flavor.GMAIL, true);
    private static final TokenProvider PROVIDER = new TokenProvider("google",
            URI.create("https://oauth2.example/token"), "client-1", () -> new Secret("s".toCharArray()));

    private final List<String> calls = new ArrayList<>();
    private TokenResponse answer;
    private final TokenEndpoint endpoint = new TokenEndpoint() {
        @Override
        public TokenResponse refresh(TokenProvider provider, char[] refreshToken) {
            throw new AssertionError("no refresh in a link");
        }

        @Override
        public TokenResponse exchangeCode(TokenProvider provider, char[] code, char[] verifier, URI redirect) {
            calls.add(provider.id() + "|" + new String(code) + "|" + new String(verifier) + "|" + redirect);
            return answer;
        }
    };

    private UpstreamCodeExchange exchange(boolean configured) {
        return new TokenEndpointCodeExchange(endpoint, id -> configured && id.equals("google")
                ? Optional.of(PROVIDER) : Optional.empty());
    }

    @Test
    void aGrantCarriesAccessTokenRefreshTokenAndScopeAndTheCallIsMadeWithVerifierAndRedirectUri() {
        answer = new TokenResponse.Granted(new Secret("at".toCharArray()), Instant.MAX,
                new Secret("rt".toCharArray()), "scope extra");

        UpstreamCodeExchange.Result r = exchange(true).exchange(INFO, "the-code".toCharArray(),
                "the-verifier".toCharArray());

        assertThat(calls).containsExactly("google|the-code|the-verifier|" + INFO.redirectUri());
        assertThat(r).isInstanceOfSatisfying(UpstreamCodeExchange.Result.Granted.class, g -> {
            assertThat(g.accessToken().chars()).isEqualTo("at".toCharArray());
            assertThat(g.refreshToken().chars()).isEqualTo("rt".toCharArray());
            assertThat(g.scope()).isEqualTo("scope extra");
        });
    }

    @Test
    void aGrantWithoutRefreshTokenHasNone() {
        answer = new TokenResponse.Granted(new Secret("at".toCharArray()), Instant.MAX, null, null);

        UpstreamCodeExchange.Result r = exchange(true).exchange(INFO, "c".toCharArray(), "v".toCharArray());

        assertThat(r).isInstanceOfSatisfying(UpstreamCodeExchange.Result.Granted.class, g -> {
            assertThat(g.refreshToken()).isNull();
            assertThat(g.scope()).isNull();
        });
    }

    @Test
    void invalidGrantIsInvalidGrantAndEveryOtherFailureIsUnavailable() {
        answer = new TokenResponse.InvalidGrant();
        assertThat(exchange(true).exchange(INFO, "c".toCharArray(), "v".toCharArray()))
                .isInstanceOf(UpstreamCodeExchange.Result.InvalidGrant.class);

        for (TokenResponse other : new TokenResponse[] {new TokenResponse.Unavailable(null),
                new TokenResponse.InvalidClient(), new TokenResponse.UnauthorizedClient(),
                new TokenResponse.Malformed(), new TokenResponse.Refused()}) {
            answer = other;
            assertThat(exchange(true).exchange(INFO, "c".toCharArray(), "v".toCharArray()))
                    .as(other.toString()).isInstanceOf(UpstreamCodeExchange.Result.Unavailable.class);
        }
    }

    @Test
    void aProviderThatIsNoLongerConfiguredIsUnavailableWithoutACall() {
        assertThat(exchange(false).exchange(INFO, "c".toCharArray(), "v".toCharArray()))
                .isInstanceOf(UpstreamCodeExchange.Result.Unavailable.class);
        assertThat(calls).isEmpty();
    }

    @Test
    void aThrowingEndpointIsUnavailableNotAnException() {
        TokenEndpoint boom = new TokenEndpoint() {
            @Override
            public TokenResponse refresh(TokenProvider provider, char[] refreshToken) {
                throw new IllegalStateException();
            }

            @Override
            public TokenResponse exchangeCode(TokenProvider provider, char[] code, char[] verifier, URI redirect) {
                throw new IllegalStateException("secret-text");
            }
        };

        assertThat(new TokenEndpointCodeExchange(boom, id -> Optional.of(PROVIDER))
                .exchange(INFO, "c".toCharArray(), "v".toCharArray()))
                .isInstanceOf(UpstreamCodeExchange.Result.Unavailable.class);
    }
}
