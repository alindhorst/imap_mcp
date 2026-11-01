package de.alexanderlindhorst.imapmcp.core.tool;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import de.alexanderlindhorst.imapmcp.core.error.ErrorCode;
import de.alexanderlindhorst.imapmcp.core.error.ToolError;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class InputValidatorTest {

    /** Sample schema from the ticket; LinkedHashMap keeps the property order deterministic. */
    static Map<String, Object> sample() {
        Map<String, Object> props = new LinkedHashMap<>();
        props.put("account", Map.of("type", "string", "minLength", 1, "maxLength", 64));
        props.put("page_size", Map.of("type", "integer", "minimum", 1, "maximum", 100));
        props.put("cursor", Map.of("type", "string", "maxLength", 512));
        props.put("date_from", Map.of("type", "string", "format", "date"));
        props.put("sent_at", Map.of("type", "string", "format", "date-time"));
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", props);
        schema.put("required", List.of("account"));
        schema.put("additionalProperties", false);
        return schema;
    }

    private final InputValidator v = new InputValidator(sample());

    private ToolError fail(Map<String, Object> args) {
        InputValidator.Result r = v.validate(args, "rid");
        assertThat(r.valid()).isFalse();
        assertThat(r.error().code()).isEqualTo(ErrorCode.INVALID_ARGUMENT);
        assertThat(r.error().requestId()).isEqualTo("rid");
        return r.error();
    }

    private static void assertDetails(ToolError e, String field, String reason, Integer max) {
        assertThat(e.details()).containsEntry("field", field).containsEntry("reason", reason);
        if (max == null) {
            assertThat(e.details()).doesNotContainKey("max");
        } else {
            assertThat(e.details()).containsEntry("max", max);
        }
    }

    @Test
    void validArgsPass() {
        InputValidator.Result r = v.validate(Map.of("account", "acc_x", "page_size", 100, "date_from", "2026-02-28"), "rid");
        assertThat(r.valid()).isTrue();
        assertThat(r.args()).containsEntry("page_size", 100);
    }

    @Test
    void unknownProperty() {
        assertDetails(fail(Map.of("account", "a", "bogus", 1)), "bogus", "unknown_property", null);
    }

    @Test
    void missingRequired() {
        assertDetails(fail(Map.of()), "account", "required", null);
    }

    @Test
    void nullOnRequiredIsMissing() {
        Map<String, Object> a = new HashMap<>();
        a.put("account", null);
        assertDetails(fail(a), "account", "required", null);
    }

    @Test
    void pageSizeBelowAndAboveBounds() {
        assertDetails(fail(Map.of("account", "a", "page_size", 0)), "page_size", "out_of_range", null);
        assertDetails(fail(Map.of("account", "a", "page_size", 101)), "page_size", "out_of_range", 100);
    }

    @Test
    void typeMismatch() {
        assertDetails(fail(Map.of("account", "a", "page_size", "5")), "page_size", "type", null);
        assertDetails(fail(Map.of("account", "a", "page_size", 5.5)), "page_size", "type", null);
        assertDetails(fail(Map.of("account", "a", "page_size", true)), "page_size", "type", null);
        assertDetails(fail(Map.of("account", 5)), "account", "type", null);
    }

    @Test
    void integralDoubleCountsAsInteger() {
        assertThat(v.validate(Map.of("account", "a", "page_size", 5.0), "rid").valid()).isTrue();
    }

    @Test
    void invalidDate() {
        assertDetails(fail(Map.of("account", "a", "date_from", "2026-13-01")), "date_from", "format", null);
        assertDetails(fail(Map.of("account", "a", "date_from", "2026-02-30")), "date_from", "format", null);
    }

    @Test
    void dateTimeRequiresOffset() {
        assertThat(v.validate(Map.of("account", "a", "sent_at", "2026-01-02T03:04:05+01:00"), "r").valid()).isTrue();
        assertThat(v.validate(Map.of("account", "a", "sent_at", "2026-01-02T03:04:05Z"), "r").valid()).isTrue();
        assertDetails(fail(Map.of("account", "a", "sent_at", "2026-01-02T03:04:05")), "sent_at", "format", null);
    }

    @Test
    void tooLongStringReportsMax() {
        assertDetails(fail(Map.of("account", "a".repeat(65))), "account", "out_of_range", 64);
        assertDetails(fail(Map.of("account", "")), "account", "out_of_range", null);
    }

    @Test
    void nullOnOptionalFieldIsAbsent() {
        Map<String, Object> a = new HashMap<>();
        a.put("account", "acc_x");
        a.put("cursor", null);
        InputValidator.Result r = v.validate(a, "rid");
        assertThat(r.valid()).isTrue();
        assertThat(r.args()).containsOnlyKeys("account");
        assertThat(a).containsKey("cursor");
    }

    @Test
    void firstErrorInDeterministicOrder() {
        // unknown properties first (sorted), then required, then schema property order
        Map<String, Object> a = new HashMap<>();
        a.put("zzz", 1);
        a.put("bbb", 1);
        a.put("page_size", 0);
        assertDetails(fail(a), "bbb", "unknown_property", null);
        assertDetails(fail(Map.of("page_size", 0, "date_from", "x")), "account", "required", null);
        assertDetails(fail(Map.of("account", "a", "page_size", 0, "date_from", "x")), "page_size", "out_of_range", null);
    }

    @Test
    void arraysEnumsPatternsAndNesting() {
        Map<String, Object> props = new LinkedHashMap<>();
        props.put("uids", Map.of("type", "array", "minItems", 1, "maxItems", 3, "items", Map.of("type", "integer", "minimum", 1)));
        props.put("mode", Map.of("type", "string", "enum", List.of("a", "b")));
        props.put("tag", Map.of("type", "string", "pattern", "^[a-z]+$"));
        props.put("flag", Map.of("type", "boolean"));
        props.put("limit", Map.of("type", "number", "maximum", 2.5));
        props.put("nested", Map.of("type", "object", "properties", Map.of("x", Map.of("type", "string")),
                "additionalProperties", false));
        InputValidator iv = new InputValidator(Map.of("type", "object", "properties", props, "additionalProperties", false));
        assertThat(iv.validate(Map.of("uids", List.of(1, 2), "mode", "a", "tag", "abc", "flag", true, "limit", 2.5), "r").valid()).isTrue();
        assertThat(iv.validate(Map.of("uids", List.of(1, 2, 3, 4)), "r").error().details()).containsEntry("field", "uids").containsEntry("max", 3);
        assertThat(iv.validate(Map.of("uids", List.of()), "r").error().details()).containsEntry("reason", "out_of_range");
        assertThat(iv.validate(Map.of("uids", List.of(0)), "r").error().details()).containsEntry("field", "uids[0]");
        assertThat(iv.validate(Map.of("uids", "x"), "r").error().details()).containsEntry("reason", "type");
        assertThat(iv.validate(Map.of("mode", "c"), "r").error().details()).containsEntry("field", "mode").containsEntry("reason", "out_of_range");
        assertThat(iv.validate(Map.of("tag", "ABC"), "r").error().details()).containsEntry("reason", "format");
        assertThat(iv.validate(Map.of("flag", "yes"), "r").error().details()).containsEntry("reason", "type");
        assertThat(iv.validate(Map.of("limit", 3), "r").error().details()).containsEntry("reason", "out_of_range");
        assertThat(iv.validate(Map.of("nested", Map.of("y", 1)), "r").error().details())
                .containsEntry("field", "nested.y").containsEntry("reason", "unknown_property");
        Map<String, Object> inner = new HashMap<>();
        inner.put("x", null);
        assertThat(iv.validate(Map.of("nested", inner), "r").args().get("nested")).isEqualTo(Map.of());
    }

    @Test
    void unsupportedKeywordsFailAtDefinitionTime() {
        for (String kw : new String[] {"oneOf", "anyOf", "allOf", "$ref", "not", "patternProperties", "const", "multipleOf", "uniqueItems"}) {
            Map<String, Object> s = new LinkedHashMap<>(sample());
            s.put(kw, List.of());
            assertThatThrownBy(() -> new InputValidator(s)).as(kw).isInstanceOf(IllegalArgumentException.class).hasMessageContaining(kw);
        }
        assertThatThrownBy(() -> new InputValidator(Map.of("type", "object", "properties",
                Map.of("p", Map.of("type", "string", "format", "email")))))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("email");
        assertThatThrownBy(() -> new InputValidator(Map.of("type", "object", "additionalProperties", true)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new InputValidator(Map.of("type", "object", "properties",
                Map.of("p", Map.of("type", "string", "pattern", "[")))))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void toolDefinitionFailsFastOnUnsupportedInputSchema() {
        Map<String, Object> bad = Map.of("type", "object", "oneOf", List.of());
        assertThatThrownBy(() -> new ToolDefinition("n", "t", "d", bad, Map.of("type", "object"), ToolHints.READ_ONLY_OPEN_WORLD))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void outOfRangeMessagesNeverEchoValues() {
        ToolError e = fail(Map.of("account", "SECRET-" + "x".repeat(70)));
        assertThat(e.toMap().toString()).doesNotContain("SECRET");
    }
}
