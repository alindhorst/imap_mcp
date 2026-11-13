package de.alexanderlindhorst.imapmcp.it;

import static org.assertj.core.api.Assertions.assertThat;

import de.alexanderlindhorst.imapmcp.app.config.PublicConfig;
import de.alexanderlindhorst.imapmcp.app.config.PublicConfigLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The generated configuration of the XOAUTH2 smoke stack (#328) loads, without a database or a provider. */
class OAuthSmokeStackConfigTest {

    @TempDir
    Path dir;

    private PublicConfig load(SmokeEnv.Provider p, String tenant, boolean purge) throws Exception {
        String pre = "IMAP_MCP_SMOKE_" + p.name() + "_";
        Map<String, String> smoke = new HashMap<>(Map.of(pre + "USER", "me@example.org", pre + "OAUTH_CLIENT_ID",
                "client-id", pre + "OAUTH_CLIENT_SECRET_REF", "env:SMOKE_CLIENT_SECRET",
                pre + "OAUTH_REFRESH_TOKEN", "r"));
        if (tenant != null) {
            smoke.put(pre + "OAUTH_TENANT", tenant);
        }
        SmokeOAuthEnv.Settings s = SmokeOAuthEnv.settings(p, smoke).orElseThrow();
        Path file = dir.resolve(p.name() + purge + ".yaml");
        Files.writeString(file, OAuthSmokeStack.config("jdbc:postgresql://localhost:5432/x", "u", s, purge));
        Map<String, String> env = Map.of("SMOKE_STACK_DB_PW", "pw", "SMOKE_STACK_WEB_SECRET", "w",
                "SMOKE_STACK_HANDLE_KEY", "k", "SMOKE_STACK_KEK", "k", "IMAP_MCP_HTTP_PORT", "8081");
        return PublicConfigLoader.load(file, env);
    }

    @Test
    void googleLoadsWithTheFixedEndpointsAndPurgeOff() throws Exception {
        PublicConfig c = load(SmokeEnv.Provider.GMAIL, null, false);
        assertThat(c.oauth().upstreamProviders()).singleElement().satisfies(p -> {
            assertThat(p.id()).isEqualTo("google");
            assertThat(p.imapHost()).isEqualTo("imap.gmail.com");
        });
        assertThat(c.http().stateful()).isFalse();
    }

    @Test
    void microsoftLoadsWithTheTenantAndPurgeOnNeedsTheStatefulTransport() throws Exception {
        PublicConfig c = load(SmokeEnv.Provider.M365, "consumers", true);
        assertThat(c.oauth().upstreamProviders()).singleElement().satisfies(p -> {
            assertThat(p.id()).isEqualTo("microsoft");
            assertThat(p.tokenEndpoint()).contains("/consumers/");
        });
        assertThat(c.http().stateful()).isTrue();
    }
}
