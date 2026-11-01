package de.alexanderlindhorst.imapmcp.core.mime;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class CharsetResolverTest {

    @Test
    void knownCharsetsResolveToCanonicalJavaNames() {
        assertThat(CharsetResolver.resolve("windows-1252").name()).isEqualTo("windows-1252");
        assertThat(CharsetResolver.resolve("latin1").name()).isEqualTo("ISO-8859-1");
        assertThat(CharsetResolver.resolve("UTF-8")).isEqualTo(StandardCharsets.UTF_8);
        assertThat(CharsetResolver.resolve("  iso-8859-1 ").name()).isEqualTo("ISO-8859-1");
    }

    @Test
    void missingOrUnknownFallBackToUtf8() {
        assertThat(CharsetResolver.resolve(null)).isEqualTo(StandardCharsets.UTF_8);
        assertThat(CharsetResolver.resolve("")).isEqualTo(StandardCharsets.UTF_8);
        assertThat(CharsetResolver.resolve("x-unknown")).isEqualTo(StandardCharsets.UTF_8);
        assertThat(CharsetResolver.resolve("bad name!\u202E")).isEqualTo(StandardCharsets.UTF_8);
    }
}
