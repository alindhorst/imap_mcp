package de.alexanderlindhorst.imapmcp.app.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import de.alexanderlindhorst.imapmcp.core.account.MailAccount.Security;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/** SPEC 5.7 (v0.29): {@code oauth.upstream.providers.<id>} and its startup failures, each naming the config path. */
class UpstreamProviderConfigTest {

    private static final String WEB = "  webClientSecret: env:WEB_SECRET\n";

    /** The valid http-public file with the given lines (already indented by 6) under oauth.upstream.providers. */
    private static String withProviders(String providers) {
        return PublicConfigLoaderTest.VALID.replace(WEB,
                WEB + "  upstream:\n    providers:\n" + providers);
    }

    private static PublicConfig bind(String providers) {
        // the fixed IMAP hosts of google and microsoft must be allowed like any other (SPEC 5.7)
        return PublicConfigLoader.bindYaml(withProviders(providers).replace(
                "allowedHosts: [\"imap.example.com\"",
                "allowedHosts: [\"imap.gmail.com\", \"outlook.office365.com\", \"imap.example.com\""), Map.of());
    }

    private static final String GENERIC = block("""
            corp:
              kind: generic
              clientId: cid
              clientSecret: file:/run/secrets/corp
              authorizationEndpoint: https://login.corp.example.net/authorize
              tokenEndpoint: https://login.corp.example.net/token
              scopes: ["https://imap.example.com/.default", "offline_access"]
              imap:
                host: imap.example.com
                port: 993
                security: implicit-tls
            """);

    /** Indents a block to the provider level (6 spaces). */
    private static String block(String text) {
        StringBuilder out = new StringBuilder();
        for (String line : text.split("\n")) {
            out.append("      ").append(line).append('\n');
        }
        return out.toString();
    }

    private static String provider(String id, String body) {
        return block(id + ":\n" + body.lines().map(l -> "  " + l).reduce("", (a, b) -> a + b + "\n"));
    }

    private static void fails(String providers, String message) {
        assertThatThrownBy(() -> bind(providers)).isInstanceOf(ConfigException.class).hasMessageContaining(message);
    }

    @Test
    void noUpstreamBlockMeansNoProviders() {
        assertThat(PublicConfigLoader.bindYaml(PublicConfigLoaderTest.VALID, Map.of()).oauth().upstreamProviders())
                .isEmpty();
    }

    @Test
    void googleHasTheFixedValuesVerbatimAndTheDerivedRedirectUri() {
        UpstreamProvider p = bind(provider("google", """
                kind: google
                clientId: gid
                clientSecret: env:G_SECRET
                label: Gmail
                """)).oauth().upstreamProviders().getFirst();
        assertThat(p.id()).isEqualTo("google");
        assertThat(p.kind()).isEqualTo(UpstreamProvider.Kind.GOOGLE);
        assertThat(p.clientId()).isEqualTo("gid");
        assertThat(p.label()).isEqualTo("Gmail");
        assertThat(p.clientSecret().value()).isEqualTo("env:G_SECRET");
        assertThat(p.authorizationEndpoint()).isEqualTo("https://accounts.google.com/o/oauth2/v2/auth");
        assertThat(p.tokenEndpoint()).isEqualTo("https://oauth2.googleapis.com/token");
        assertThat(p.tokenHost()).isEqualTo("oauth2.googleapis.com");
        assertThat(p.scopes()).containsExactly("https://mail.google.com/");
        assertThat(p.imapHost()).isEqualTo("imap.gmail.com");
        assertThat(p.imapPort()).isEqualTo(993);
        assertThat(p.imapSecurity()).isEqualTo(Security.IMPLICIT_TLS);
        assertThat(p.protocol()).isEqualTo("gimaps");
        assertThat(p.redirectUri()).isEqualTo("https://mail.example.com/accounts/oauth/google/callback");
        assertThat(p.tenant()).isNull();
    }

    @Test
    void microsoftHasTheFixedValuesVerbatimAndTenantDefaultsToOrganizations() {
        UpstreamProvider p = bind(provider("ms-work", """
                kind: microsoft
                clientId: mid
                clientSecret: env:M_SECRET
                """)).oauth().upstreamProviders().getFirst();
        assertThat(p.kind()).isEqualTo(UpstreamProvider.Kind.MICROSOFT);
        assertThat(p.label()).isEqualTo("ms-work");
        assertThat(p.tenant()).isEqualTo("organizations");
        assertThat(p.authorizationEndpoint())
                .isEqualTo("https://login.microsoftonline.com/organizations/oauth2/v2.0/authorize");
        assertThat(p.tokenEndpoint()).isEqualTo("https://login.microsoftonline.com/organizations/oauth2/v2.0/token");
        assertThat(p.tokenHost()).isEqualTo("login.microsoftonline.com");
        assertThat(p.scopes()).containsExactly("https://outlook.office.com/IMAP.AccessAsUser.All", "offline_access");
        assertThat(p.imapHost()).isEqualTo("outlook.office365.com");
        assertThat(p.imapPort()).isEqualTo(993);
        assertThat(p.imapSecurity()).isEqualTo(Security.IMPLICIT_TLS);
        assertThat(p.protocol()).isEqualTo("imaps");
        assertThat(p.redirectUri()).isEqualTo("https://mail.example.com/accounts/oauth/ms-work/callback");
    }

    @Test
    void microsoftTenantAcceptsTheFixedNamesAndAGuid() {
        for (String tenant : List.of("common", "organizations", "consumers",
                "0b4c5e1a-9f3d-4a52-8e61-2c7d3f9a1b40")) {
            UpstreamProvider p = bind(provider("ms", "kind: microsoft\nclientId: m\nclientSecret: env:M\ntenant: "
                    + tenant + "\n")).oauth().upstreamProviders().getFirst();
            assertThat(p.tenant()).isEqualTo(tenant);
            assertThat(p.tokenEndpoint()).isEqualTo("https://login.microsoftonline.com/" + tenant
                    + "/oauth2/v2.0/token");
        }
    }

    @Test
    void genericTakesItsEndpointsScopesAndImapSettingsFromTheFile() {
        UpstreamProvider p = bind(GENERIC).oauth().upstreamProviders().getFirst();
        assertThat(p.kind()).isEqualTo(UpstreamProvider.Kind.GENERIC);
        assertThat(p.clientSecret().value()).isEqualTo("file:/run/secrets/corp");
        assertThat(p.authorizationEndpoint()).isEqualTo("https://login.corp.example.net/authorize");
        assertThat(p.tokenEndpoint()).isEqualTo("https://login.corp.example.net/token");
        assertThat(p.tokenHost()).isEqualTo("login.corp.example.net");
        assertThat(p.scopes()).containsExactly("https://imap.example.com/.default", "offline_access");
        assertThat(p.imapHost()).isEqualTo("imap.example.com");
        assertThat(p.imapPort()).isEqualTo(993);
        assertThat(p.protocol()).isEqualTo("imaps");
        assertThat(p.redirectUri()).isEqualTo("https://mail.example.com/accounts/oauth/corp/callback");
    }

    @Test
    void genericStarttlsOnPort143UsesTheImapProtocolKey() {
        UpstreamProvider p = bind(GENERIC.replace("port: 993", "port: 143").replace("implicit-tls", "starttls"))
                .oauth().upstreamProviders().getFirst();
        assertThat(p.imapSecurity()).isEqualTo(Security.STARTTLS);
        assertThat(p.imapPort()).isEqualTo(143);
        assertThat(p.protocol()).isEqualTo("imap");
    }

    @Test
    void severalProvidersKeepTheirOrderAndTheModelIsImmutable() {
        List<UpstreamProvider> all = bind(provider("a-google", "kind: google\nclientId: g\nclientSecret: env:G\n")
                + provider("b-ms", "kind: microsoft\nclientId: m\nclientSecret: env:M\n")).oauth().upstreamProviders();
        assertThat(all).extracting(UpstreamProvider::id).containsExactly("a-google", "b-ms");
        assertThatThrownBy(() -> all.add(all.getFirst())).isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void theToStringHidesTheClientIdAndTheSecretReference() {
        String s = bind(GENERIC).oauth().upstreamProviders().getFirst().toString();
        assertThat(s).doesNotContain("cid").doesNotContain("/run/secrets/corp");
    }

    // ---- startup failures, each naming the path ----

    @Test
    void aKindIsRequiredKnownAndLowerCase() {
        fails(provider("p", "clientId: c\nclientSecret: env:S\n"), "oauth.upstream.providers.p.kind: required");
        for (String kind : List.of("Google", "GOOGLE", "yahoo")) {
            fails(provider("p", "kind: " + kind + "\nclientId: c\nclientSecret: env:S\n"),
                    "oauth.upstream.providers.p.kind: must be one of generic, google, microsoft");
        }
    }

    @Test
    void aClientIdIsRequired() {
        fails(provider("p", "kind: google\nclientSecret: env:S\n"), "oauth.upstream.providers.p.clientId: required");
    }

    @Test
    void aClientSecretIsRequiredAndMustBeAnEnvOrFileReference() {
        fails(provider("p", "kind: google\nclientId: c\n"), "oauth.upstream.providers.p.clientSecret: required");
        for (String bad : List.of("literal-secret-VALUE", "keyring:svc/acct", "env:", "file:")) {
            assertThatThrownBy(() -> bind(provider("p", "kind: google\nclientId: c\nclientSecret: \"" + bad + "\"\n")))
                    .isInstanceOf(ConfigException.class)
                    .hasMessageContaining("oauth.upstream.providers.p.clientSecret: must be a secret reference "
                            + "(env: or file:)")
                    .hasMessageNotContaining("VALUE");
        }
    }

    @Test
    void aMalformedProviderIdIsRefusedNamingThePath() {
        for (String id : List.of("Google", "a_b", "\"a b\"", "x".repeat(33))) {
            fails(provider(id, "kind: google\nclientId: c\nclientSecret: env:S\n"),
                    "oauth.upstream.providers: malformed provider id (must match [a-z0-9-]{1,32})");
        }
    }

    @Test
    void aDuplicateProviderIdIsRefusedNamingThePath() {
        String two = provider("dup", "kind: google\nclientId: c\nclientSecret: env:S\n")
                + provider("dup", "kind: google\nclientId: d\nclientSecret: env:S\n");
        assertThatThrownBy(() -> bind(two)).isInstanceOf(ConfigException.class)
                .hasMessageContaining("oauth.upstream.providers.dup: duplicate key");
    }

    @Test
    void unknownKeysAreRefused() {
        fails(provider("p", "kind: google\nclientId: c\nclientSecret: env:S\nfoo: 1\n"),
                "oauth.upstream.providers.p.foo: unknown key");
    }

    @ParameterizedTest
    @MethodSource("endpointKeysOnFixedKinds")
    void endpointScopeAndImapKeysAreRefusedOnGoogleAndMicrosoft(String kind, String key, String value) {
        fails(provider("p", "kind: " + kind + "\nclientId: c\nclientSecret: env:S\n" + key + ": " + value + "\n"),
                "oauth.upstream.providers.p." + key + ": not allowed with kind " + kind);
    }

    static Stream<Arguments> endpointKeysOnFixedKinds() {
        return Stream.of("google", "microsoft").flatMap(kind -> Stream.of(
                Arguments.of(kind, "authorizationEndpoint", "https://x.example.com/a"),
                Arguments.of(kind, "tokenEndpoint", "https://x.example.com/t"),
                Arguments.of(kind, "scopes", "[\"a\"]"),
                Arguments.of(kind, "imap", "{host: imap.example.com}")));
    }

    @Test
    void aTenantIsOnlyForMicrosoftAndMustBeValid() {
        fails(provider("p", "kind: google\nclientId: c\nclientSecret: env:S\ntenant: common\n"),
                "oauth.upstream.providers.p.tenant: not allowed with kind google");
        for (String bad : List.of("Common", "evil.example.com/x", "not-a-guid", "0b4c5e1a-9f3d-4a52-8e61")) {
            fails(provider("p", "kind: microsoft\nclientId: c\nclientSecret: env:S\ntenant: " + bad + "\n"),
                    "oauth.upstream.providers.p.tenant: must be one of");
        }
    }

    @ParameterizedTest
    @MethodSource("badGenericEndpoints")
    void aGenericEndpointMustBeHttpsWithoutUserInfoOrFragment(String key, String value, String message) {
        String yaml = GENERIC.replaceAll(key + ": \\S+", key + ": " + value);
        fails(yaml, "oauth.upstream.providers.corp." + key + ": " + message);
    }

    static Stream<Arguments> badGenericEndpoints() {
        String msg = "must be an https URI without user info or fragment";
        return Stream.of("authorizationEndpoint", "tokenEndpoint").flatMap(k -> Stream.of(
                Arguments.of(k, "http://login.corp.example.net/x", msg),
                Arguments.of(k, "https://user:pw@login.corp.example.net/x", msg),
                Arguments.of(k, "https://login.corp.example.net/x#frag", msg),
                Arguments.of(k, "not-a-uri", msg),
                Arguments.of(k, "https:///x", msg)));
    }

    @Test
    void aGenericEndpointMayCarryAQuery() {
        String yaml = GENERIC.replace("https://login.corp.example.net/token", "https://login.corp.example.net/token?a=b");
        assertThat(bind(yaml).oauth().upstreamProviders().getFirst().tokenEndpoint())
                .isEqualTo("https://login.corp.example.net/token?a=b");
    }

    @Test
    void aGenericTokenHostThatIsAnIpLiteralIsRefused() {
        for (String host : List.of("10.0.0.1", "[::1]", "[2001:db8::1]")) {
            fails(GENERIC.replace("login.corp.example.net/token", host + "/token"),
                    "oauth.upstream.providers.corp.tokenEndpoint: host must be a DNS name, not an IP literal");
        }
    }

    @Test
    void genericRequiresEndpointsScopesAndImap() {
        for (String key : List.of("authorizationEndpoint", "tokenEndpoint", "scopes", "imap")) {
            String yaml = GENERIC.replaceAll("(?m)^ {8}" + key + ":.*\\n( {10}.*\\n)*", "");
            fails(yaml, "oauth.upstream.providers.corp." + key + ": required");
        }
    }

    @Test
    void genericScopesMustNotBeEmpty() {
        fails(GENERIC.replaceAll("scopes: \\[.*]", "scopes: []"), "oauth.upstream.providers.corp.scopes: must not be empty");
        fails(GENERIC.replaceAll("scopes: \\[.*]", "scopes: [\"\"]"),
                "oauth.upstream.providers.corp.scopes[0]: must not be blank");
    }

    @Test
    void genericImapPortAndSecurityFollowTheSsrfRule() {
        fails(GENERIC.replace("port: 993", "port: 143"),
                "oauth.upstream.providers.corp.imap.port: must be 993 with implicit-tls or 143 with starttls");
        fails(GENERIC.replace("security: implicit-tls", "security: starttls"),
                "oauth.upstream.providers.corp.imap.port: must be 993 with implicit-tls or 143 with starttls");
        fails(GENERIC.replace("port: 993", "port: 1993"),
                "oauth.upstream.providers.corp.imap.port: must be 993 with implicit-tls or 143 with starttls");
        fails(GENERIC.replace("security: implicit-tls", "security: plain"),
                "oauth.upstream.providers.corp.imap.security: must be one of implicit-tls, starttls");
    }

    @Test
    void genericImapPortAndSecurityDefaultTogether() {
        UpstreamProvider p = bind(GENERIC.replace("          port: 993\n", "").replace("          security: implicit-tls\n", ""))
                .oauth().upstreamProviders().getFirst();
        assertThat(p.imapPort()).isEqualTo(993);
        assertThat(p.imapSecurity()).isEqualTo(Security.IMPLICIT_TLS);
    }

    @Test
    void aGenericImapHostOutsideTheSsrfAllowlistIsRefused() {
        fails(GENERIC.replace("host: imap.example.com", "host: imap.other.example.org"),
                "oauth.upstream.providers.corp.imap.host: not matched by ssrf.allowedHosts");
        fails(GENERIC.replace("host: imap.example.com", "host: 10.1.2.3"),
                "oauth.upstream.providers.corp.imap.host: not matched by ssrf.allowedHosts");
        // a suffix pattern matches
        assertThat(bind(GENERIC.replace("host: imap.example.com", "host: imap.mail.example.net"))
                .oauth().upstreamProviders()).hasSize(1);
    }

    @Test
    void theFixedImapHostsOfGoogleAndMicrosoftMustBeInTheSsrfAllowlistToo() {
        String google = provider("g1", "kind: google\nclientId: c\nclientSecret: env:S\n");
        String ms = provider("m1", "kind: microsoft\nclientId: c\nclientSecret: env:S\n");
        assertThatThrownBy(() -> PublicConfigLoader.bindYaml(withProviders(google), Map.of()))
                .isInstanceOf(ConfigException.class)
                .hasMessageContaining("oauth.upstream.providers.g1").hasMessageContaining("ssrf.allowedHosts");
        assertThatThrownBy(() -> PublicConfigLoader.bindYaml(withProviders(ms), Map.of()))
                .isInstanceOf(ConfigException.class)
                .hasMessageContaining("oauth.upstream.providers.m1").hasMessageContaining("ssrf.allowedHosts");
        String anyHost = withProviders(google + ms).replace(
                "allowedHosts: [\"imap.example.com\", \"*.mail.example.net\"]", "allowAnyPublicHost: true");
        assertThat(PublicConfigLoader.bindYaml(anyHost, Map.of()).oauth().upstreamProviders()).hasSize(2);
    }

    @Test
    void withAllowAnyPublicHostTheImapHostIsNotMatchedAgainstTheList() {
        String yaml = withProviders(GENERIC.replace("host: imap.example.com", "host: imap.other.example.org"))
                .replace("allowedHosts: [\"imap.example.com\", \"*.mail.example.net\"]",
                        "allowAnyPublicHost: true");
        assertThat(PublicConfigLoader.bindYaml(yaml, Map.of()).oauth().upstreamProviders()).hasSize(1);
    }

    @Test
    void onlyProvidersIsAcceptedUnderUpstream() {
        String yaml = PublicConfigLoaderTest.VALID.replace(WEB, WEB + "  upstream:\n    other: 1\n");
        assertThatThrownBy(() -> PublicConfigLoader.bindYaml(yaml, Map.of())).isInstanceOf(ConfigException.class)
                .hasMessageContaining("oauth.upstream.other: unknown key");
    }
}
