package de.alexanderlindhorst.imapmcp.transport.http.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;
import de.alexanderlindhorst.imapmcp.core.account.Secret;
import java.net.URI;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class WebSupportTest {

    private static WebConfig insecureConfig(String base, String issuer, String role, boolean allowWithoutRole) {
        return new WebConfig(URI.create(base), issuer, "client", () -> new Secret("s".toCharArray()), "sub", role,
                null, allowWithoutRole, true);
    }

    private static WebConfig config(String base, String issuer, String role, boolean allowWithoutRole) {
        return new WebConfig(URI.create(base), issuer, "client", () -> new Secret("s".toCharArray()), "sub", role,
                null, allowWithoutRole);
    }

    @Test
    void htmlEscapingCoversTheFiveSpecialCharacters() {
        assertThat(Html.escape("<a href=\"x\" onclick='y'>&</a>"))
                .isEqualTo("&lt;a href=&quot;x&quot; onclick=&#39;y&#39;&gt;&amp;&lt;/a&gt;");
        assertThat(Html.page("<t>", "", "<p>x</p>")).contains("<title>&lt;t&gt;</title>");
    }

    @Test
    void rolesAreReadFromANestedClaimPathOrAPlainClaim() {
        Map<String, Object> claims = Map.of("realm_access", Map.of("roles", List.of("a", "imap-user")),
                "roles", List.of("x"), "single", "only", "num", 5);

        assertThat(Roles.has(claims, "realm_access.roles", "imap-user")).isTrue();
        assertThat(Roles.has(claims, "realm_access.roles", "admin")).isFalse();
        assertThat(Roles.has(claims, "roles", "x")).isTrue();
        assertThat(Roles.has(claims, "single", "only")).isTrue();
        assertThat(Roles.has(claims, "num", "5")).isFalse();
        assertThat(Roles.has(claims, "realm_access.missing", "a")).isFalse();
        assertThat(Roles.has(claims, "roles.deeper", "x")).isFalse();
    }

    @Test
    void theWebConfigNeedsAnHttpsBaseUrlWithoutPathAndARoleOrTheExplicitSwitch() {
        assertThat(config("https://mail.example.com", "https://idp", "r", false).redirectUri())
                .isEqualTo("https://mail.example.com/accounts/callback");
        assertThat(config("https://Mail.Example.com:8443/", "https://idp", "r", false).origin())
                .isEqualTo("https://mail.example.com:8443");
        for (String bad : new String[] {"http://mail.example.com", "https://mail.example.com/app",
                "https://mail.example.com?x=1", "https://u@mail.example.com", "https://mail.example.com#f"}) {
            assertThatThrownBy(() -> config(bad, "https://idp", "r", false)).as(bad)
                    .isInstanceOf(IllegalArgumentException.class);
        }
        assertThatThrownBy(() -> config("https://m.example.com", "https://idp", null, false))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(config("https://m.example.com", "https://idp", null, true).requiredRole()).isNull();
        assertThat(config("https://m.example.com", "https://idp", "r", false).roleClaim())
                .isEqualTo("realm_access.roles");
        assertThat(config("https://m.example.com", "https://idp", "r", false).toString()).doesNotContain("client");
    }

    @Test
    void anIssuerOverPlainHttpIsNotUsedUnlessTheTestSwitchIsOn() throws Exception {
        try (FakeIdp idp = new FakeIdp()) {
            WebConfig insecure = insecureConfig("https://m.example.com", idp.issuer, "r", false);
            var keys = new ImmutableJWKSet<com.nimbusds.jose.proc.SecurityContext>(new JWKSet(idp.key.toPublicJWK()));

            assertThat(HttpOidcProvider.create(insecure, keys).authorizationEndpoint().toString())
                    .startsWith(idp.issuer);
        }
    }

    @Test
    void discoveryWithAnotherIssuerIsRefused() throws Exception {
        try (FakeIdp idp = new FakeIdp()) {
            WebConfig c = insecureConfig("https://m.example.com", idp.issuer + "/", "r", false); // differs from the document
            var keys = new ImmutableJWKSet<com.nimbusds.jose.proc.SecurityContext>(new JWKSet(idp.key.toPublicJWK()));

            assertThatThrownBy(() -> HttpOidcProvider.create(c, keys).authorizationEndpoint())
                    .isInstanceOf(OidcException.class);
        }
    }

    @Test
    void theIssuerMustBeHttpsUnlessTheTestSwitchIsOn() {
        for (String bad : new String[] {"http://idp.example/realms/x", "https://u@idp.example", "https://idp.example?x=1",
                "https://idp.example#f", "idp.example", "ftp://idp.example"}) {
            assertThatThrownBy(() -> config("https://m.example.com", bad, "r", false)).as(bad)
                    .isInstanceOf(IllegalArgumentException.class);
        }
        assertThat(config("https://m.example.com", "https://idp.example/realms/x", "r", false).issuer())
                .isEqualTo("https://idp.example/realms/x");
        assertThat(new WebConfig(URI.create("https://m.example.com"), "http://127.0.0.1:1/realms/x", "client",
                () -> new Secret("s".toCharArray()), "sub", "r", null, false, true).issuer())
                .isEqualTo("http://127.0.0.1:1/realms/x");
    }

    @Test
    void anExplicitDefaultHttpsPortInTheBaseUrlIsNormalised() {
        WebConfig c = config("https://Mail.Example.com:443", "https://idp", "r", false);

        assertThat(c.origin()).isEqualTo("https://mail.example.com");
        assertThat(c.authority()).isEqualTo("mail.example.com");
        assertThat(c.redirectUri()).isEqualTo("https://mail.example.com/accounts/callback");
        assertThat(config("https://mail.example.com:8443", "https://idp", "r", false).authority())
                .isEqualTo("mail.example.com:8443");
    }
}
