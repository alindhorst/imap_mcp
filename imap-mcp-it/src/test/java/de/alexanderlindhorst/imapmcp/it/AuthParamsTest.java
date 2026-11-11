package de.alexanderlindhorst.imapmcp.it;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.junit.jupiter.api.Test;

/** #430: RFC 9110 section 11 challenge parsing (auth-scheme, auth-param, quoted-string). No Docker. */
class AuthParamsTest {

    @Test
    void parsesTheServersChallenge() {
        List<AuthParams.Challenge> c = AuthParams.parse("Bearer realm=\"imap-mcp\", "
                + "resource_metadata=\"https://mcp.example/.well-known/oauth-protected-resource/mcp\", "
                + "scope=\"imap-mcp:read imap-mcp:write\"");
        assertThat(c).hasSize(1);
        assertThat(c.getFirst().scheme()).isEqualToIgnoringCase("Bearer");
        assertThat(c.getFirst().param("resource_metadata"))
                .isEqualTo("https://mcp.example/.well-known/oauth-protected-resource/mcp");
        assertThat(c.getFirst().param("scope")).isEqualTo("imap-mcp:read imap-mcp:write");
        assertThat(c.getFirst().param("realm")).isEqualTo("imap-mcp");
    }

    @Test
    void aQuotedStringMayHoldCommasAndEscapes() {
        AuthParams.Challenge c = AuthParams.parse("Bearer scope=\"a, b\", error=\"x\\\"y\\\\z\"").getFirst();
        assertThat(c.param("scope")).isEqualTo("a, b");
        assertThat(c.param("error")).isEqualTo("x\"y\\z");
    }

    @Test
    void tokenValuesNamesAreCaseInsensitiveAndWhitespaceIsTolerated() {
        AuthParams.Challenge c = AuthParams.parse("bearer  Realm=imap ,  ERROR = \"invalid_token\"").getFirst();
        assertThat(c.param("realm")).isEqualTo("imap");
        assertThat(c.param("error")).isEqualTo("invalid_token");
    }

    @Test
    void severalChallengesInOneHeaderAreSplit() {
        List<AuthParams.Challenge> c = AuthParams.parse("Basic realm=\"x\", Bearer scope=\"s\", resource_metadata=\"u\"");
        assertThat(c).extracting(AuthParams.Challenge::scheme).containsExactly("Basic", "Bearer");
        assertThat(c.get(1).param("resource_metadata")).isEqualTo("u");
        assertThat(c.get(0).param("scope")).isNull();
    }

    @Test
    void aStringThatIsNotAChallengeIsRejected() {
        assertThatThrownBy(() -> AuthParams.parse("Bearer scope=\"unterminated")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> AuthParams.parse("Bearer a=\"1\", a=\"2\"")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> AuthParams.parse("realm=\"x\"")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> AuthParams.parse("")).isInstanceOf(IllegalArgumentException.class);
    }
}
