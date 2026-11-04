package de.alexanderlindhorst.imapmcp.core.tool;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** #25: published patterns are the portable ECMA-262 subset, anchored ^...$ and matched against the whole value. */
class EcmaPatternTest {

    @ParameterizedTest
    @ValueSource(strings = {
            "^[\\x21\\x23\\x24\\x26\\x27\\x2b-\\x5b\\x5e-\\x7a\\x7c-\\x7e]+$",
            "^[^\\u0000-\\u001F\\u007F-\\u009F\\x25\\x2a]+$",
            "^[a-z0-9]+$", "^abc$", "^a*b+c?$", "^(?:ab)+$", "^(?:a\\|b)$", "^\\x41\\u0042$",
            "^\\$\\^\\\\\\.\\*\\+\\?\\(\\)\\[\\]\\{\\}\\|\\/$", "^[^a-z]+$"})
    void accepts(String pattern) {
        assertThatCode(() -> EcmaPattern.check(pattern)).doesNotThrowAnyException();
    }

    @Test
    void acceptsTheKeywordConstant() {
        assertThatCode(() -> EcmaPattern.check(ImapAtoms.KEYWORD_PATTERN)).doesNotThrowAnyException();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "\\Aabc\\z", "^abc\\z", "\\Aabc$", "^abc\\Z", "abc$", "^abc", "^abc\\$", "^a|b$", "^a.$", "^\\s+$",
            "^\\w+$", "^\\bx$", "^a\\b$", "[a&&b]", "^[a&&b]$", "^[a[b]]$", "^a*+$", "^a++$", "^a+?$", "^(?>a)$",
            "^(?i)a$", "^\\p{L}$", "^\\uD83D$", "^a{2}$", "^[%]$", "^(a)$", "^(?=a)a$", "^(?!a)b$", "^(?<=a)b$",
            "^\\Ga$", "^[a-]$", "^[z-a]$", "^[]$", "^[a$", "^(?:a$", "^$", "^[\\xZZ]$", "^\\u12$", "^a\\$",
            "^a b$", "^-$", "^[\\d]$"})
    void rejects(String pattern) {
        assertThatThrownBy(() -> EcmaPattern.check(pattern)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void toolDefinitionWithNonPortablePatternFailsFast() {
        Map<String, Object> schema = Map.of("type", "object", "properties",
                Map.of("p", Map.of("type", "string", "pattern", "\\Aabc\\z")));
        assertThatThrownBy(() -> new InputValidator(schema)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void nonPortablePatternMessageNamesTheLocationButNotThePattern() {
        Map<String, Object> schema = Map.of("type", "object", "properties",
                Map.of("p", Map.of("type", "string", "pattern", "\\Asecret\\z")));
        assertThatThrownBy(() -> new InputValidator(schema)).hasMessageStartingWith("invalid pattern at ")
                .hasMessageContaining("p").hasMessageNotContaining("secret");
    }

    @Test
    void validatorMatchesTheWholeValue() {
        Map<String, Object> schema = Map.of("type", "object", "properties",
                Map.of("p", Map.of("type", "string", "pattern", "^[a-z]+$")));
        InputValidator v = new InputValidator(schema);
        assertThat(v.validate(Map.of("p", "abc"), "rid").valid()).isTrue();
        for (String bad : List.of("abc\n", "abc\r\n", "abc\r", "abc\u0085", "abc\u2028", "abc\u2029")) {
            assertThat(v.validate(Map.of("p", bad), "rid").valid()).as("%d", bad.length()).isFalse();
        }
    }
}
