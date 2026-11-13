package de.alexanderlindhorst.imapmcp.core.oauth;

import static org.assertj.core.api.Assertions.assertThat;

import de.alexanderlindhorst.imapmcp.core.account.Secret;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class AccessTokenTest {

    @Test
    void toStringNeverContainsTheValue() {
        AccessToken t = new AccessToken(new Secret("canary-token-123".toCharArray()), Instant.parse("2030-01-01T00:00:00Z"));
        assertThat(t.toString()).doesNotContain("canary").contains("***");
    }

    @Test
    void closeWipesTheValue() {
        char[] chars = "canary".toCharArray();
        AccessToken t = new AccessToken(new Secret(chars), Instant.EPOCH);
        t.close();
        assertThat(chars).containsOnly('\0');
    }
}
