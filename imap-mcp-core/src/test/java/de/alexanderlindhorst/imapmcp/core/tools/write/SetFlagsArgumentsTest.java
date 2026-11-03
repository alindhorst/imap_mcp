package de.alexanderlindhorst.imapmcp.core.tools.write;

import static org.assertj.core.api.Assertions.assertThat;

import de.alexanderlindhorst.imapmcp.core.error.ToolError;
import de.alexanderlindhorst.imapmcp.core.tool.DescriptionRules;
import de.alexanderlindhorst.imapmcp.core.tool.InputValidator;
import de.alexanderlindhorst.imapmcp.core.tool.ToolHints;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

class SetFlagsArgumentsTest {

    private static final InputValidator VALIDATOR = new InputValidator(SetFlagsTool.DEFINITION.inputSchema());

    private static Map<String, Object> args(Object... kv) {
        Map<String, Object> m = new HashMap<>();
        m.put("account", "acc");
        m.put("handles", List.of("h1", "h2"));
        for (int i = 0; i < kv.length; i += 2) {
            m.put((String) kv[i], kv[i + 1]);
        }
        return m;
    }

    /** Schema validation, then the code checks, exactly as the tool will run them. */
    private static SetFlagsTool.Parsed run(Map<String, Object> args) {
        InputValidator.Result r = VALIDATOR.validate(args, "rid");
        if (!r.valid()) {
            return new SetFlagsTool.Parsed(null, null, null, r.error());
        }
        return SetFlagsTool.parse(r.args(), "rid");
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> details(ToolError e) {
        return (Map<String, Object>) ((Map<String, Object>) e.toMap().get("error")).get("details");
    }

    private static void assertInvalid(Map<String, Object> args, String field, String reason) {
        SetFlagsTool.Parsed p = run(args);
        assertThat(p.valid()).isFalse();
        assertThat(details(p.error())).containsEntry("field", field).containsEntry("reason", reason);
    }

    @Test
    void definitionHintsAndName() {
        assertThat(SetFlagsTool.DEFINITION.name()).isEqualTo("set_flags");
        assertThat(SetFlagsTool.DEFINITION.hints()).isEqualTo(ToolHints.WRITE_DESTRUCTIVE_IDEMPOTENT);
        ToolHints h = SetFlagsTool.DEFINITION.hints();
        assertThat(h.destructive()).isTrue();
        assertThat(h.idempotent()).isTrue();
        assertThat(h.readOnly()).isFalse();
        assertThat(h.openWorld()).isTrue();
    }

    @Test
    @SuppressWarnings("unchecked")
    void definitionSchemaShape() {
        Map<String, Object> s = SetFlagsTool.DEFINITION.inputSchema();
        assertThat(s.get("additionalProperties")).isEqualTo(false);
        assertThat(s.get("required")).isEqualTo(List.of("account", "handles"));
        Map<String, Object> props = (Map<String, Object>) s.get("properties");
        assertThat(props.keySet()).containsExactly("account", "handles", "seen", "flagged", "answered", "draft",
                "deleted", "add_keywords", "remove_keywords");
        Map<String, Object> handles = (Map<String, Object>) props.get("handles");
        assertThat(handles).containsEntry("minItems", 1).containsEntry("maxItems", 100)
                .containsEntry("uniqueItems", true);
        assertThat((Map<String, Object>) props.get("deleted")).containsEntry("enum", List.of(false));
        for (String k : List.of("add_keywords", "remove_keywords")) {
            Map<String, Object> kw = (Map<String, Object>) props.get(k);
            assertThat(kw).containsEntry("maxItems", 10).containsEntry("uniqueItems", true);
            Map<String, Object> item = (Map<String, Object>) kw.get("items");
            assertThat(item).containsEntry("minLength", 1).containsEntry("maxLength", 64)
                    .containsEntry("pattern", de.alexanderlindhorst.imapmcp.core.search.SearchSchema.KEYWORD_PATTERN);
        }
    }

    @Test
    void descriptionFollowsRulesAndNamesUndoAndGmail() {
        assertThat(DescriptionRules.violations(SetFlagsTool.DEFINITION)).isEmpty();
        String d = SetFlagsTool.DEFINITION.description();
        assertThat(d).contains(DescriptionRules.WRITE_SENTENCE);
        assertThat(d).containsIgnoringCase("undo").contains("opposite value");
        assertThat(d).contains("Gmail").containsIgnoringCase("labels");
    }

    @Test
    void deletedTrueIsOutOfRange() {
        assertInvalid(args("deleted", true), "deleted", "out_of_range");
    }

    @Test
    void reservedKeywordsIgnoringCase() {
        for (String k : List.of("seen", "ANSWERED", "Flagged", "deleted", "DRAFT", "Recent")) {
            assertInvalid(args("add_keywords", List.of("ok", k)), "add_keywords[1]", "reserved");
            assertInvalid(args("remove_keywords", List.of(k)), "remove_keywords[0]", "reserved");
        }
    }

    @Test
    void caseInsensitiveDuplicateInOneList() {
        assertInvalid(args("add_keywords", List.of("Work", "work")), "add_keywords[1]", "duplicate");
        assertInvalid(args("remove_keywords", List.of("a", "b", "B")), "remove_keywords[2]", "duplicate");
    }

    @Test
    void sameKeywordInBothListsConflicts() {
        assertInvalid(args("add_keywords", List.of("x", "Work"), "remove_keywords", List.of("y", "WORK")),
                "remove_keywords[1]", "conflict");
    }

    @Test
    void noChangeIsRejected() {
        assertInvalid(args(), "seen", "no_change");
        assertInvalid(args("add_keywords", List.of(), "remove_keywords", List.of()), "seen", "no_change");
        SetFlagsTool.Parsed p = run(args());
        String hint = (String) ((Map<?, ?>) p.error().toMap().get("error")).get("hint");
        assertThat(hint).contains("seen", "flagged", "answered", "draft", "deleted", "add_keywords",
                "remove_keywords");
    }

    @Test
    void tooManyHandlesIsOutOfRangeMax100() {
        List<String> hs = new ArrayList<>(IntStream.range(0, 101).mapToObj(i -> "h" + i).toList());
        SetFlagsTool.Parsed p = run(args("handles", hs));
        assertThat(details(p.error())).containsEntry("field", "handles").containsEntry("reason", "out_of_range")
                .containsEntry("max", 100);
        assertThat(run(args("handles", List.of())).valid()).isFalse();
    }

    @Test
    void jsonNullOnOptionalFieldsIsAbsent() {
        Map<String, Object> a = args();
        for (String k : List.of("seen", "flagged", "answered", "draft", "deleted", "add_keywords",
                "remove_keywords")) {
            a.put(k, null);
        }
        assertInvalid(a, "seen", "no_change");
        a.put("flagged", true);
        SetFlagsTool.Parsed p = run(a);
        assertThat(p.valid()).isTrue();
        assertThat(p.add()).containsExactly("\\Flagged");
        assertThat(p.remove()).isEmpty();
    }

    @Test
    void systemFlagsAndKeywordsAreMergedIntoOneAddAndOneRemoveSet() {
        SetFlagsTool.Parsed p = run(args("seen", true, "flagged", false, "answered", true, "draft", false,
                "deleted", false, "add_keywords", List.of("Work", "todo"), "remove_keywords", List.of("old")));
        assertThat(p.valid()).isTrue();
        assertThat(p.handles()).containsExactly("h1", "h2");
        assertThat(p.add()).containsExactly("\\Seen", "\\Answered", "Work", "todo");
        assertThat(p.remove()).containsExactly("\\Flagged", "\\Draft", "\\Deleted", "old");
    }

    @Test
    void deletedFalseAloneGoesIntoRemoveSet() {
        SetFlagsTool.Parsed p = run(args("deleted", false));
        assertThat(p.valid()).isTrue();
        assertThat(p.add()).isEmpty();
        assertThat(p.remove()).containsExactly("\\Deleted");
    }

    @Test
    void keywordsAreKeptAsGiven() {
        SetFlagsTool.Parsed p = run(args("add_keywords", Arrays.asList("MixedCase")));
        assertThat(p.add()).containsExactly("MixedCase");
    }
}
