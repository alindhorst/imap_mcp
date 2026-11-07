package de.alexanderlindhorst.imapmcp.app.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/** SPEC 5.7 (v0.20): the http-public configuration and its startup failures, each naming the key path. */
class PublicConfigLoaderTest {

    static final String VALID = """
            db:
              url: jdbc:postgresql://db:5432/imapmcp
              username: imapmcp
              password: env:DB_PW
            oauth:
              issuer: https://idp.example.com/realms/test
              resource: https://mail.example.com/mcp
              requiredRole: imap-mcp-user
              webClientId: imap-mcp-web
              webClientSecret: env:WEB_SECRET
            public:
              baseUrl: https://mail.example.com
            security:
              handleKeys:
                - id: 1
                  key: env:HANDLE_KEY_1
                - id: 2
                  key: env:HANDLE_KEY_2
              signingKeyId: 2
            ssrf:
              allowedHosts: ["imap.example.com", "*.mail.example.net"]
            kms:
              provider: local
              local:
                currentKekId: kek-2024
                keks:
                  - id: kek-2023
                    key: env:KEK_2023
                  - id: kek-2024
                    key: env:KEK_2024
            """;

    private static PublicConfig bind(String yaml) {
        return PublicConfigLoader.bindYaml(yaml, Map.of());
    }

    private static String without(String key) {
        // drops one top-level block from VALID
        StringBuilder out = new StringBuilder();
        boolean skipping = false;
        for (String line : VALID.split("\n")) {
            if (!line.startsWith(" ") && !line.isEmpty()) {
                skipping = line.startsWith(key + ":");
            }
            if (!skipping) {
                out.append(line).append('\n');
            }
        }
        return out.toString();
    }

    @Test
    void aCompleteFileBindsWithTheSpecDefaults() {
        PublicConfig c = bind(VALID);
        assertThat(c.db().url()).isEqualTo("jdbc:postgresql://db:5432/imapmcp");
        assertThat(c.db().maxPoolSize()).isEqualTo(10);
        assertThat(c.oauth().subjectClaim()).isEqualTo("sub");
        assertThat(c.oauth().roleClaim()).isEqualTo("realm_access.roles");
        assertThat(c.oauth().requiredRole()).isEqualTo("imap-mcp-user");
        assertThat(c.oauth().allowWithoutRole()).isFalse();
        assertThat(c.pub().maxAccountsPerTenant()).isEqualTo(10);
        assertThat(c.security().signingKeyId()).isEqualTo(2);
        assertThat(c.security().handleKeys()).hasSize(2);
        assertThat(c.ssrf().allowedHosts()).containsExactly("imap.example.com", "*.mail.example.net");
        assertThat(c.ssrf().allowAnyPublicHost()).isFalse();
        assertThat(c.kms().provider()).isEqualTo("local");
        assertThat(c.kms().currentKekId()).isEqualTo("kek-2024");
        assertThat(c.kms().keks()).extracting(PublicConfig.Kek::id).containsExactly("kek-2023", "kek-2024");
        assertThat(c.tools().inlineMaxBytes()).isEqualTo(5L * 1024 * 1024);
        assertThat(c.tools().inlineConcurrency()).isEqualTo(4);
        assertThat(c.tools().deadline()).isEqualTo(Duration.ofSeconds(90));
        assertThat(c.http().stateful()).isFalse();
        assertThat(c.purgeEnabled()).isFalse();
    }

    @Test
    void thePublicBaseUrlIsNormalisedToTheOriginBrowsersSend() {
        String base = "baseUrl: https://mail.example.com";
        assertThat(bind(VALID.replace(base, "baseUrl: https://MAIL.Example.com:443")).pub().baseUrl())
                .isEqualTo("https://mail.example.com");
        assertThat(bind(VALID.replace(base, "baseUrl: https://mail.example.com/")).pub().baseUrl())
                .isEqualTo("https://mail.example.com");
        assertThat(bind(VALID.replace(base, "baseUrl: https://mail.example.com:8443")).pub().baseUrl())
                .isEqualTo("https://mail.example.com:8443");
        assertThat(bind(VALID.replace(base, "baseUrl: https://mail.example.com:8443"
                + "\n  maxAccountsPerTenant: 25")).pub().maxAccountsPerTenant()).isEqualTo(25);
    }

    @Test
    void theWebClientSecretIsHeldAsAReferenceAndItsToStringHidesIt() {
        PublicConfig c = bind(VALID);
        assertThat(c.oauth().webClientId()).isEqualTo("imap-mcp-web");
        assertThat(c.oauth().webClientSecret().value()).isEqualTo("env:WEB_SECRET");
        assertThat(c.oauth().webClientSecret().toString()).doesNotContain("WEB_SECRET");
    }

    @Test
    void theHostCheckDefaultsToTheHostOfThePublicBaseUrl() {
        assertThat(bind(VALID).http().allowedHosts()).containsExactly("mail.example.com:*");
        String explicit = VALID + "http:\n  allowedHosts: [\"edge.example.com\"]\n";
        assertThat(bind(explicit).http().allowedHosts()).containsExactly("edge.example.com");
    }

    @Test
    void aConfiguredAccountsListFailsStartup() {
        String withAccounts = VALID + "accounts:\n  - id: a\n    host: h\n    username: u\n    password: env:X\n";
        assertThatThrownBy(() -> bind(withAccounts)).isInstanceOf(ConfigException.class).hasMessageContaining(
                "config.accounts: not available in http-public (accounts are linked in the linking surface)");
    }

    @Test
    void theDatabasePoolSizeIsBoundedTo100() {
        assertThat(bind(VALID.replace("username: imapmcp", "username: imapmcp\n  maxPoolSize: 100")).db().maxPoolSize())
                .isEqualTo(100);
        assertThatThrownBy(() -> bind(VALID.replace("username: imapmcp", "username: imapmcp\n  maxPoolSize: 101")))
                .hasMessageContaining("db.maxPoolSize");
    }

    @Test
    void passwordsInTheJdbcUrlAreRefused() {
        for (String q : new String[] {"?password=x", "?user=a&PASSWORD=x", "?sslpassword=x", "?a=b&sslPassword=x"}) {
            assertThatThrownBy(() -> bind(VALID.replace("5432/imapmcp", "5432/imapmcp" + q)))
                    .hasMessageContaining("db.url").hasMessageNotContaining("=x");
        }
        assertThat(bind(VALID.replace("5432/imapmcp", "5432/imapmcp?sslmode=require")).db().url()).contains("sslmode");
    }

    static final String VAULT_KMS = """
            kms:
              provider: vault
              vault:
                address: https://vault.example.com:8200
                keyName: imap-mcp
                auth:
                  method: token
                  token: env:VAULT_TOKEN
            """;

    private static String withVault(String kmsBlock) {
        return without("kms") + kmsBlock;
    }

    @Test
    void aVaultConfigurationBindsWithTheSpecDefaults() {
        PublicConfig c = bind(withVault(VAULT_KMS));
        assertThat(c.kms().provider()).isEqualTo("vault");
        assertThat(c.kms().keks()).isEmpty();
        var v = c.kms().vault();
        assertThat(v.address().toString()).isEqualTo("https://vault.example.com:8200");
        assertThat(v.transitMount()).isEqualTo("transit");
        assertThat(v.keyName()).isEqualTo("imap-mcp");
        assertThat(v.requestTimeout()).isEqualTo(Duration.ofSeconds(5));
        assertThat(v.allowInsecureHttp()).isFalse();
        assertThat(v.caCert()).isNull();
        assertThat(v.auth()).isInstanceOf(de.alexanderlindhorst.imapmcp.store.crypto.VaultAuth.Token.class);
    }

    @Test
    void anApproleConfigurationBindsWithTheDefaultPath() {
        String yaml = withVault(VAULT_KMS.replace("method: token\n      token: env:VAULT_TOKEN",
                "method: approle\n      roleId: env:RID\n      secretId: env:SID"));
        var auth = (de.alexanderlindhorst.imapmcp.store.crypto.VaultAuth.AppRole) bind(yaml).kms().vault().auth();
        assertThat(auth.path()).isEqualTo("approle");
    }

    static Stream<Arguments> vaultFailures() {
        return Stream.of(
                Arguments.of(VAULT_KMS.replace("    address: https://vault.example.com:8200\n", ""), "kms.vault.address: required"),
                Arguments.of(VAULT_KMS.replace("https://vault", "http://vault"), "kms.vault.address"),
                Arguments.of(VAULT_KMS.replace("    keyName: imap-mcp\n", ""), "kms.vault.keyName: required"),
                Arguments.of(VAULT_KMS.replace("keyName: imap-mcp", "keyName: bad key!"), "kms.vault.keyName"),
                Arguments.of(VAULT_KMS.replace("    auth:\n      method: token\n      token: env:VAULT_TOKEN\n", ""),
                        "kms.vault.auth"),
                Arguments.of(VAULT_KMS.replace("method: token", "method: ldap"), "kms.vault.auth.method: must be one of"),
                Arguments.of(VAULT_KMS.replace("      token: env:VAULT_TOKEN\n", ""), "kms.vault.auth.token: required"),
                Arguments.of(VAULT_KMS.replace("token: env:VAULT_TOKEN", "token: literal"), "kms.vault.auth.token: must be a secret reference"),
                Arguments.of(VAULT_KMS.replace("method: token", "method: approle").replace("token: env:VAULT_TOKEN",
                        "roleId: env:R\n      secretId: env:S\n      token: env:T"),
                        "kms.vault.auth.token: not allowed with kms.vault.auth.method approle"),
                Arguments.of(VAULT_KMS.replace("method: token\n      token: env:VAULT_TOKEN", "method: approle\n      roleId: env:R"),
                        "kms.vault.auth.secretId: required"),
                Arguments.of(VAULT_KMS.replace("method: token", "method: token\n      roleId: env:R"),
                        "kms.vault.auth.roleId: not allowed with kms.vault.auth.method token"),
                Arguments.of(VAULT_KMS.replace("method: token\n      token: env:VAULT_TOKEN",
                        "method: approle\n      path: bad path!\n      roleId: env:R\n      secretId: env:S"),
                        "kms.vault.auth.path"),
                Arguments.of(VAULT_KMS.replace("keyName: imap-mcp", "keyName: imap-mcp\n    bogus: 1"),
                        "kms.vault.bogus: unknown key"),
                Arguments.of(VAULT_KMS.replace("method: token", "method: token\n      bogus: 1"),
                        "kms.vault.auth.bogus: unknown key"));
    }

    @ParameterizedTest
    @MethodSource("vaultFailures")
    void vaultStartupFailuresNameTheKeyPath(String kms, String expected) {
        assertThatThrownBy(() -> bind(withVault(kms))).isInstanceOf(ConfigException.class)
                .hasMessageContaining(expected);
    }

    @Test
    void vaultConfigurationMessagesNeverEchoValues() {
        for (String bad : new String[] {"https://user:HUNTER2@vault.example.com", "https://vault.example.com/path?x=HUNTER2",
                "ht!tp://HUNTER2", "https://va ult.example.com/HUNTER2"}) {
            assertThatThrownBy(() -> bind(withVault(VAULT_KMS.replace("https://vault.example.com:8200", bad))))
                    .isInstanceOf(ConfigException.class).hasMessageContaining("kms.vault.address")
                    .hasMessageNotContaining("HUNTER2");
        }
    }

    @Test
    void vaultTimeoutCaCertAndInsecureHttpAreChecked() {
        assertThat(bind(withVault(VAULT_KMS.replace("keyName: imap-mcp", "keyName: imap-mcp\n    requestTimeout: 10s\n"
                + "    transitMount: secrets/transit"))).kms().vault().requestTimeout()).isEqualTo(Duration.ofSeconds(10));
        assertThatThrownBy(() -> bind(withVault(VAULT_KMS.replace("keyName: imap-mcp",
                "keyName: imap-mcp\n    requestTimeout: 31s")))).hasMessageContaining("kms.vault.requestTimeout");
        assertThatThrownBy(() -> bind(withVault(VAULT_KMS.replace("keyName: imap-mcp",
                "keyName: imap-mcp\n    caCert: relative/ca.pem")))).hasMessageContaining("kms.vault.caCert");
        assertThat(bind(withVault(VAULT_KMS.replace("https://vault", "http://vault").replace("keyName: imap-mcp",
                "keyName: imap-mcp\n    allowInsecureHttp: true"))).kms().vault().allowInsecureHttp()).isTrue();
    }

    @Test
    void localKeysAreNotAllowedWithVaultAndTheVaultSectionIsRequired() {
        assertThatThrownBy(() -> bind(VALID.replace("provider: local", "provider: vault")))
                .isInstanceOf(ConfigException.class).hasMessageContaining("kms.local: not allowed with kms.provider vault");
        assertThatThrownBy(() -> bind(withVault("kms:\n  provider: vault\n")))
                .hasMessageContaining("kms.vault: required");
    }

    @Test
    void imapTraceTrueFailsStartupWithTheSpecMessageAndFalseIsAccepted() {
        assertThatThrownBy(() -> bind(VALID + "imap:\n  trace: true\n")).isInstanceOf(ConfigException.class)
                .hasMessage("config.imap.trace: not available in http-public "
                        + "(the trace would write every tenant's mail content into the operator log)");
        assertThat(bind(VALID + "imap:\n  trace: false\n").imap().trace()).isFalse();
        assertThat(bind(VALID).imap().trace()).isFalse();
    }

    @Test
    void optionalKeysAreBound() {
        String yaml = VALID.replace("requiredRole: imap-mcp-user", "allowWithoutRole: true\n  subjectClaim: oid\n  roleClaim: roles")
                + "purge:\n  enabled: false\nhttp:\n  stateful: true\ntools:\n  download:\n    inlineMaxBytes: 1MiB\n"
                + "    inlineConcurrency: 2\n";
        PublicConfig c = bind(yaml);
        assertThat(c.oauth().requiredRole()).isNull();
        assertThat(c.oauth().allowWithoutRole()).isTrue();
        assertThat(c.oauth().webClientId()).isEqualTo("imap-mcp-web");
        assertThat(c.oauth().webClientSecret()).isNotNull();
        assertThat(c.oauth().subjectClaim()).isEqualTo("oid");
        assertThat(c.tools().inlineMaxBytes()).isEqualTo(1024 * 1024);
        assertThat(c.tools().inlineConcurrency()).isEqualTo(2);
        assertThat(c.http().stateful()).isTrue();
    }

    static Stream<Arguments> failures() {
        return Stream.of(
                Arguments.of(without("db"), "db.url: required"),
                Arguments.of(VALID.replace("password: env:DB_PW", "password: literal"), "db.password: must be a secret reference"),
                Arguments.of(without("oauth"), "oauth.issuer: required"),
                Arguments.of(VALID.replace("https://idp.example.com/realms/test", "http://idp.example.com/realms/test"),
                        "oauth.issuer: must be an https URI"),
                Arguments.of(VALID.replace("https://mail.example.com/mcp", "http://mail.example.com/mcp"),
                        "oauth.resource: must be an https URI"),
                Arguments.of(VALID.replace("  requiredRole: imap-mcp-user\n", ""),
                        "oauth.requiredRole: required unless oauth.allowWithoutRole is true"),
                Arguments.of(without("public"), "public.baseUrl: required"),
                Arguments.of(VALID.replace("  webClientId: imap-mcp-web\n", ""), "oauth.webClientId: required"),
                Arguments.of(VALID.replace("  webClientSecret: env:WEB_SECRET\n", ""),
                        "oauth.webClientSecret: required"),
                Arguments.of(VALID.replace("webClientSecret: env:WEB_SECRET", "webClientSecret: literal"),
                        "oauth.webClientSecret: must be a secret reference"),
                Arguments.of(VALID.replace("webClientId: imap-mcp-web", "webClientId: \"\""),
                        "oauth.webClientId: must not be blank"),
                Arguments.of(VALID.replace("baseUrl: https://mail.example.com", "baseUrl: https://mail.example.com/app"),
                        "public.baseUrl: must be an https URI without path"),
                Arguments.of(VALID.replace("baseUrl: https://mail.example.com", "baseUrl: https://mail.example.com/?a=1"),
                        "public.baseUrl: must be an https URI without path"),
                Arguments.of(VALID.replace("baseUrl: https://mail.example.com", "baseUrl: https://mail.example.com#frag"),
                        "public.baseUrl: must be an https URI without path"),
                Arguments.of(VALID.replace("baseUrl: https://mail.example.com", "baseUrl: \"https://mail.example.com?\""),
                        "public.baseUrl: must be an https URI without path"),
                Arguments.of(VALID.replace("baseUrl: https://mail.example.com", "baseUrl: https://u@mail.example.com"),
                        "public.baseUrl: must be an https URI without path"),
                Arguments.of(VALID.replace("baseUrl: https://mail.example.com", "baseUrl: https://mail.example.com\n  maxAccountsPerTenant: 0"),
                        "public.maxAccountsPerTenant"),
                Arguments.of(VALID.replace("baseUrl: https://mail.example.com", "baseUrl: http://mail.example.com"),
                        "public.baseUrl: must be an https URI"),
                Arguments.of(without("security"), "security.handleKeys: required"),
                Arguments.of(VALID.replace("signingKeyId: 2\n", ""), "security.signingKeyId: required"),
                Arguments.of(VALID.replace("signingKeyId: 2", "signingKeyId: 9"),
                        "security.signingKeyId: no key with this id in security.handleKeys"),
                Arguments.of(VALID.replace("security:\n", "security:\n  handleKey: env:OLD\n"),
                        "security.handleKey: not available in http-public"),
                Arguments.of(without("ssrf"), "ssrf.allowedHosts: required unless ssrf.allowAnyPublicHost is true"),
                Arguments.of(without("kms"), "kms.provider: required"),
                Arguments.of(VALID.replace("provider: local", "provider: azure"), "kms.provider: must be one of local, vault"),
                Arguments.of(VALID.replace("currentKekId: kek-2024", "currentKekId: kek-9"),
                        "kms.local.currentKekId: not one of kms.local.keks"),
                Arguments.of(VALID.replace("- id: kek-2023", "- id: kek-2024"), "kms.local.keks[1].id: duplicate id"),
                Arguments.of(VALID.replace("- id: kek-2023", "- id: bad id!"), "kms.local.keks[0].id: must match"),
                Arguments.of(VALID.replace("currentKekId: kek-2024\n", "currentKekId: kek-2024\n    bogus: 1\n"),
                        "kms.local.bogus: unknown key"),
                Arguments.of(VALID.replace("provider: local\n", "provider: local\n  vault:\n    address: https://v\n"),
                        "kms.vault: not allowed with kms.provider local"),
                Arguments.of(VALID + "tools:\n  download:\n    dir: /tmp/x\n", "tools.download.dir: not available in http-public"),
                Arguments.of(VALID + "tools:\n  download:\n    maxBytes: 1MiB\n", "tools.download.maxBytes: not available in http-public"),
                Arguments.of(VALID + "tools:\n  download:\n    displayDir: /Downloads\n",
                        "tools.download.displayDir: not available in http-public"),
                Arguments.of(VALID + "tools:\n  download:\n    inlineMaxBytes: 11MiB\n", "tools.download.inlineMaxBytes"),
                Arguments.of(VALID + "tools:\n  download:\n    inlineConcurrency: 0\n", "tools.download.inlineConcurrency"),
                Arguments.of(VALID + "http:\n  token: env:T\n", "http.token: not available in http-public"),
                Arguments.of(VALID + "purge:\n  enabled: true\n", "purge.enabled: requires http.stateful"),
                Arguments.of(VALID + "bogus: 1\n", "bogus: unknown key"),
                Arguments.of(VALID.replace("oauth:\n", "oauth:\n  extra: 1\n"), "oauth.extra: unknown key"));
    }

    @ParameterizedTest
    @MethodSource("failures")
    void startupFailuresNameTheKeyPath(String yaml, String expected) {
        assertThatThrownBy(() -> bind(yaml)).isInstanceOf(ConfigException.class).hasMessageContaining(expected);
    }

    @Test
    void errorsNeverEchoValues() {
        String secretLooking = VALID.replace("password: env:DB_PW", "password: hunter2-SECRET");
        assertThatThrownBy(() -> bind(secretLooking)).hasMessageNotContaining("hunter2");
    }

    @Test
    void theHttpEnvironmentOverridesApply() {
        PublicConfig c = PublicConfigLoader.bindYaml(VALID,
                Map.of("IMAP_MCP_HTTP_BIND", "0.0.0.0", "IMAP_MCP_HTTP_PORT", "9090",
                        "IMAP_MCP_HTTP_ALLOW_NON_LOOPBACK_BIND", "true", "IMAP_MCP_LOG_LEVEL", "debug"));
        assertThat(c.http().bind()).isEqualTo("0.0.0.0");
        assertThat(c.http().port()).isEqualTo(9090);
        assertThat(c.http().allowNonLoopbackBind()).isTrue();
        assertThat(c.logLevel()).isEqualTo("DEBUG");
    }

    @Test
    void anAllowAnyPublicHostFileNeedsNoAllowlist() {
        String yaml = VALID.replace("allowedHosts: [\"imap.example.com\", \"*.mail.example.net\"]",
                "allowAnyPublicHost: true");
        PublicConfig c = bind(yaml);
        assertThat(c.ssrf().allowAnyPublicHost()).isTrue();
        assertThat(c.ssrf().allowedHosts()).isEmpty();
        assertThat(List.of(c.ssrf().extraBlockedCidrs())).isNotNull();
    }
}
