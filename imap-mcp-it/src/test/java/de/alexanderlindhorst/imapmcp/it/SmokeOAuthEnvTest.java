package de.alexanderlindhorst.imapmcp.it;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import de.alexanderlindhorst.imapmcp.it.SmokeEnv.Provider;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** The environment parsing and redaction of the XOAUTH2 smoke harness (#328) in the normal build. */
class SmokeOAuthEnvTest {

    private static final String REFRESH = "1//CANARY-refresh-token-77";

    private static Map<String, String> gmail() {
        Map<String, String> env = new HashMap<>();
        env.put("IMAP_MCP_SMOKE_GMAIL_USER", "someone@example.org");
        env.put("IMAP_MCP_SMOKE_GMAIL_OAUTH_CLIENT_ID", "client-id-1");
        env.put("IMAP_MCP_SMOKE_GMAIL_OAUTH_CLIENT_SECRET_REF", "env:SMOKE_GOOGLE_SECRET");
        env.put("IMAP_MCP_SMOKE_GMAIL_OAUTH_REFRESH_TOKEN", REFRESH);
        return env;
    }

    @Test
    void emptyEnvironmentIsSkippedWithAReasonNamingEveryVariableAndNoSecretValue() {
        assertThat(SmokeOAuthEnv.settings(Provider.GMAIL, Map.of())).isEmpty();
        assertThat(SmokeOAuthEnv.skipReason(Provider.GMAIL, Map.of())).get().asString()
                .contains("skipped").contains("IMAP_MCP_SMOKE_GMAIL_USER")
                .contains("IMAP_MCP_SMOKE_GMAIL_OAUTH_CLIENT_ID")
                .contains("IMAP_MCP_SMOKE_GMAIL_OAUTH_CLIENT_SECRET_REF")
                .contains("IMAP_MCP_SMOKE_GMAIL_OAUTH_REFRESH_TOKEN");
        assertThat(SmokeOAuthEnv.skipReason(Provider.M365, Map.of())).get().asString()
                .contains("IMAP_MCP_SMOKE_M365_OAUTH_REFRESH_TOKEN");
    }

    @Test
    void anyMissingOrBlankVariableSkips() {
        for (String key : gmail().keySet()) {
            Map<String, String> env = gmail();
            env.put(key, "  ");
            assertThat(SmokeOAuthEnv.skipReason(Provider.GMAIL, env)).as(key).isPresent();
            env.remove(key);
            assertThat(SmokeOAuthEnv.settings(Provider.GMAIL, env)).as(key).isEmpty();
        }
    }

    @Test
    void aCompleteEnvironmentGivesSettingsAndNoSkipReason() {
        assertThat(SmokeOAuthEnv.skipReason(Provider.GMAIL, gmail())).isEmpty();
        SmokeOAuthEnv.Settings s = SmokeOAuthEnv.settings(Provider.GMAIL, gmail()).orElseThrow();
        assertThat(s.user()).isEqualTo("someone@example.org");
        assertThat(s.clientId()).isEqualTo("client-id-1");
        assertThat(s.clientSecretRef()).isEqualTo("env:SMOKE_GOOGLE_SECRET");
        assertThat(s.refreshToken()).isEqualTo(REFRESH);
        assertThat(s.providerKind()).isEqualTo("google");
        assertThat(s.writeEnabled()).isFalse();
        assertThat(s.purgeSubject()).isEmpty();
        assertThat(s.microsoftTenant()).isEmpty();
    }

    @Test
    void theClientSecretMustBeAnEnvOrFileReferenceNeverALiteral() {
        Map<String, String> env = gmail();
        env.put("IMAP_MCP_SMOKE_GMAIL_OAUTH_CLIENT_SECRET_REF", "GOCSPX-literal-secret-value");
        assertThatThrownBy(() -> SmokeOAuthEnv.settings(Provider.GMAIL, env))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("IMAP_MCP_SMOKE_GMAIL_OAUTH_CLIENT_SECRET_REF")
                .hasMessageNotContaining("GOCSPX-literal-secret-value");
        env.put("IMAP_MCP_SMOKE_GMAIL_OAUTH_CLIENT_SECRET_REF", "file:/run/secrets/google");
        assertThat(SmokeOAuthEnv.settings(Provider.GMAIL, env)).isPresent();
    }

    @Test
    void writePurgeSubjectTenantAndRevokedTokenAreOptIn() {
        Map<String, String> env = gmail();
        env.put("IMAP_MCP_SMOKE_OAUTH_WRITE", "1");
        env.put("IMAP_MCP_SMOKE_OAUTH_PURGE_SUBJECT", "  smoke-purge-me  ");
        env.put("IMAP_MCP_SMOKE_GMAIL_OAUTH_REVOKED_REFRESH_TOKEN", "revoked-canary-9");
        SmokeOAuthEnv.Settings s = SmokeOAuthEnv.settings(Provider.GMAIL, env).orElseThrow();
        assertThat(s.writeEnabled()).isTrue();
        assertThat(s.purgeSubject()).contains("smoke-purge-me");
        assertThat(s.revokedRefreshToken()).contains("revoked-canary-9");

        env.put("IMAP_MCP_SMOKE_OAUTH_WRITE", "yes");
        assertThat(SmokeOAuthEnv.settings(Provider.GMAIL, env).orElseThrow().writeEnabled())
                .as("only the value 1 enables writes").isFalse();
    }

    @Test
    void microsoftUsesItsOwnVariablesKindAndAnOptionalTenant() {
        Map<String, String> env = new HashMap<>();
        env.put("IMAP_MCP_SMOKE_M365_USER", "me@contoso.example");
        env.put("IMAP_MCP_SMOKE_M365_OAUTH_CLIENT_ID", "c");
        env.put("IMAP_MCP_SMOKE_M365_OAUTH_CLIENT_SECRET_REF", "env:SMOKE_MS_SECRET");
        env.put("IMAP_MCP_SMOKE_M365_OAUTH_REFRESH_TOKEN", "r");
        env.put("IMAP_MCP_SMOKE_M365_OAUTH_TENANT", "consumers");
        SmokeOAuthEnv.Settings s = SmokeOAuthEnv.settings(Provider.M365, env).orElseThrow();
        assertThat(s.providerKind()).isEqualTo("microsoft");
        assertThat(s.microsoftTenant()).contains("consumers");
        assertThat(SmokeOAuthEnv.settings(Provider.GMAIL, env)).isEmpty();
    }

    @Test
    void toStringAndTheProviderYamlNeverCarryTheRefreshTokenOrAnyCredential() {
        Map<String, String> env = gmail();
        env.put("IMAP_MCP_SMOKE_GMAIL_OAUTH_REVOKED_REFRESH_TOKEN", "revoked-canary-9");
        SmokeOAuthEnv.Settings s = SmokeOAuthEnv.settings(Provider.GMAIL, env).orElseThrow();
        assertThat(s.toString()).contains("redacted").doesNotContain(REFRESH).doesNotContain("revoked-canary-9")
                .doesNotContain("someone@example.org").doesNotContain("env:SMOKE_GOOGLE_SECRET");
        assertThat(s.providerYaml()).contains("kind: google").contains("clientId: client-id-1")
                .contains("clientSecret: env:SMOKE_GOOGLE_SECRET").doesNotContain(REFRESH)
                .doesNotContain("revoked-canary-9");
    }

    @Test
    void theMicrosoftYamlCarriesTheTenantOnlyWhenSet() {
        Map<String, String> env = new HashMap<>();
        env.put("IMAP_MCP_SMOKE_M365_USER", "me@contoso.example");
        env.put("IMAP_MCP_SMOKE_M365_OAUTH_CLIENT_ID", "c");
        env.put("IMAP_MCP_SMOKE_M365_OAUTH_CLIENT_SECRET_REF", "env:SMOKE_MS_SECRET");
        env.put("IMAP_MCP_SMOKE_M365_OAUTH_REFRESH_TOKEN", "r");
        assertThat(SmokeOAuthEnv.settings(Provider.M365, env).orElseThrow().providerYaml()).contains("kind: microsoft")
                .doesNotContain("tenant:");
        env.put("IMAP_MCP_SMOKE_M365_OAUTH_TENANT", "common");
        assertThat(SmokeOAuthEnv.settings(Provider.M365, env).orElseThrow().providerYaml()).contains("tenant: common");
    }
}
