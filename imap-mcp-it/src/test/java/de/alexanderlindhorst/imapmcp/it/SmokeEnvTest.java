package de.alexanderlindhorst.imapmcp.it;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import de.alexanderlindhorst.imapmcp.it.SmokeEnv.Provider;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** The skip logic of the opt-in smoke harness (#258) in the normal build: no environment means skipped, not failed. */
class SmokeEnvTest {

    private static final Map<String, String> GMAIL = Map.of(
            "IMAP_MCP_SMOKE_GMAIL_USER", "someone@example.org", "IMAP_MCP_SMOKE_GMAIL_PASSWORD", "app-pw-1234");

    private static org.assertj.core.api.AbstractStringAssert<?> reason(java.util.Optional<String> r) {
        return assertThat(r).isPresent().get(org.assertj.core.api.InstanceOfAssertFactories.STRING);
    }

    @Test
    void emptyEnvironmentSkipsEveryProviderWithAReasonNamingTheVariables() {
        assertThat(SmokeEnv.settings(Provider.GMAIL, Map.of())).isEmpty();
        reason(SmokeEnv.skipReason(Provider.GMAIL, Map.of()))
                .contains("IMAP_MCP_SMOKE_GMAIL_USER").contains("IMAP_MCP_SMOKE_GMAIL_PASSWORD");
        reason(SmokeEnv.skipReason(Provider.M365, Map.of()))
                .contains("IMAP_MCP_SMOKE_M365_USER").contains("IMAP_MCP_SMOKE_M365_PASSWORD");
    }

    @Test
    void halfConfiguredOrBlankIsStillSkipped() {
        assertThat(SmokeEnv.settings(Provider.GMAIL, Map.of("IMAP_MCP_SMOKE_GMAIL_USER", "u"))).isEmpty();
        assertThat(SmokeEnv.settings(Provider.GMAIL,
                Map.of("IMAP_MCP_SMOKE_GMAIL_USER", "u", "IMAP_MCP_SMOKE_GMAIL_PASSWORD", "  "))).isEmpty();
    }

    @Test
    void userAndPasswordSelectTheProviderDefaults() {
        SmokeEnv.Settings s = SmokeEnv.settings(Provider.GMAIL, GMAIL).orElseThrow();
        assertThat(s.user()).isEqualTo("someone@example.org");
        assertThat(s.host()).isEqualTo("imap.gmail.com");
        assertThat(s.port()).isEqualTo(993);
        assertThat(SmokeEnv.skipReason(Provider.GMAIL, GMAIL)).isEmpty();
        assertThat(SmokeEnv.settings(Provider.GMAIL, GMAIL.entrySet().stream()
                .collect(java.util.stream.Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue))).orElseThrow())
                .isEqualTo(s);
    }

    @Test
    void hostAndPortCanBeOverridden() {
        Map<String, String> env = new java.util.HashMap<>(GMAIL);
        env.put("IMAP_MCP_SMOKE_GMAIL_HOST", "imap.googlemail.com");
        env.put("IMAP_MCP_SMOKE_GMAIL_PORT", "1993");
        SmokeEnv.Settings s = SmokeEnv.settings(Provider.GMAIL, env).orElseThrow();
        assertThat(s.host()).isEqualTo("imap.googlemail.com");
        assertThat(s.port()).isEqualTo(1993);
        env.put("IMAP_MCP_SMOKE_GMAIL_PORT", "nope");
        assertThatThrownBy(() -> SmokeEnv.settings(Provider.GMAIL, env))
                .hasMessageContaining("IMAP_MCP_SMOKE_GMAIL_PORT").hasMessageNotContaining("app-pw-1234");
    }

    @Test
    void secretsNeverAppearInToStringOrReasons() {
        SmokeEnv.Settings s = SmokeEnv.settings(Provider.GMAIL, GMAIL).orElseThrow();
        assertThat(s.toString()).doesNotContain("app-pw-1234").doesNotContain("someone@example.org");
    }

    @Test
    void writeChecksNeedTheExtraOptInAndAGmailAccount() {
        reason(SmokeEnv.writeSkipReason(Provider.GMAIL, GMAIL)).contains("IMAP_MCP_SMOKE_WRITE=1");
        Map<String, String> on = new java.util.HashMap<>(GMAIL);
        on.put("IMAP_MCP_SMOKE_WRITE", "1");
        assertThat(SmokeEnv.writeSkipReason(Provider.GMAIL, on)).isEmpty();
        on.put("IMAP_MCP_SMOKE_WRITE", "true");
        assertThat(SmokeEnv.writeSkipReason(Provider.GMAIL, on)).isPresent();
        // the opt-in alone is not enough
        reason(SmokeEnv.writeSkipReason(Provider.GMAIL, Map.of("IMAP_MCP_SMOKE_WRITE", "1")))
                .contains("IMAP_MCP_SMOKE_GMAIL_USER");
        // write checks are defined for Gmail only
        Map<String, String> m365 = Map.of("IMAP_MCP_SMOKE_M365_USER", "u", "IMAP_MCP_SMOKE_M365_PASSWORD", "p",
                "IMAP_MCP_SMOKE_WRITE", "1");
        reason(SmokeEnv.writeSkipReason(Provider.M365, m365)).contains("Gmail");
    }

    @Test
    void runLabelIsUniquePerRunAndNeverCollidesWithUserLabels() {
        String a = SmokeEnv.runLabel();
        String b = SmokeEnv.runLabel();
        assertThat(a).startsWith("imap-mcp-smoke-").isNotEqualTo(b).matches("[A-Za-z0-9-]+");
    }
}
