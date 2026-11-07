package de.alexanderlindhorst.imapmcp.transport.http;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.junit.jupiter.api.Test;

class OAuthResourceConfigTest {

    @Test
    void metadataUrlIsDerivedFromTheConfiguredResourceOnly() {
        OAuthResourceConfig c = TestTokens.config();
        assertThat(c.metadataUrl()).isEqualTo("https://mail.example.com/.well-known/oauth-protected-resource/mcp");
        assertThat(c.metadataPath()).isEqualTo("/.well-known/oauth-protected-resource/mcp");
    }

    @Test
    void portAndPathArePreserved() {
        OAuthResourceConfig c = new OAuthResourceConfig("https://idp", "https://mail.example.com:8443/api/mcp", "sub",
                List.of());
        assertThat(c.metadataUrl())
                .isEqualTo("https://mail.example.com:8443/.well-known/oauth-protected-resource/api/mcp");
    }

    @Test
    void resourceMustBeHttpsWithoutQueryOrFragment() {
        for (String bad : List.of("http://mail.example.com/mcp", "https://mail.example.com/mcp?x=1",
                "https://mail.example.com/mcp#f", "mail.example.com/mcp", "https:///mcp", "https://u@mail.example.com/mcp")) {
            assertThatThrownBy(() -> new OAuthResourceConfig("https://idp", bad, "sub", List.of()))
                    .as(bad).isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Test
    void resourcePathMustEndWithMcpWithoutATrailingSlash() {
        for (String bad : List.of("https://mail.example.com", "https://mail.example.com/",
                "https://mail.example.com/mcp/", "https://mail.example.com/x", "https://mail.example.com/xmcp",
                "https://mail.example.com/mcp/x")) {
            assertThatThrownBy(() -> new OAuthResourceConfig("https://idp", bad, "sub", List.of()))
                    .as(bad).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("oauth.resource");
        }
        for (String ok : List.of("https://mail.example.com/mcp", "https://mail.example.com/x/mcp")) {
            assertThat(new OAuthResourceConfig("https://idp", ok, "sub", List.of()).resource()).isEqualTo(ok);
        }
        assertThat(new OAuthResourceConfig("https://idp", "https://mail.example.com/x/mcp", "sub", List.of())
                .metadataPath()).isEqualTo("/.well-known/oauth-protected-resource/x/mcp");
    }

    @Test
    void issuerAndSubjectClaimMustNotBeBlank() {
        assertThatThrownBy(() -> new OAuthResourceConfig(" ", TestTokens.RESOURCE, "sub", List.of()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new OAuthResourceConfig("https://idp", TestTokens.RESOURCE, "", List.of()))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
