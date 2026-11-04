package de.alexanderlindhorst.imapmcp.core.tools.write;

import static org.assertj.core.api.Assertions.assertThat;

import de.alexanderlindhorst.imapmcp.core.error.ErrorCode;
import de.alexanderlindhorst.imapmcp.core.error.ToolError;
import de.alexanderlindhorst.imapmcp.core.tool.DescriptionRules;
import de.alexanderlindhorst.imapmcp.core.tool.ToolDefinition;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class CreateFolderArgumentsTest {

    static final ToolDefinition DEF = CreateFolderTool.DEFINITION;

    private static Map<String, Object> args(String parent, String name) {
        Map<String, Object> m = new HashMap<>();
        m.put("account", "acc");
        m.put("parent", parent);
        m.put("name", name);
        return m;
    }

    private static ToolError error(String parent, String name) {
        CreateFolderTool.Parsed p = CreateFolderTool.parse(args(parent, name), "rid");
        assertThat(p.error()).isNotNull();
        assertThat(p.error().code()).isEqualTo(ErrorCode.INVALID_ARGUMENT);
        return p.error();
    }

    private static void assertDetail(ToolError e, String field, String reason, Integer max) {
        assertThat(e.details()).containsEntry("field", field).containsEntry("reason", reason);
        if (max == null) {
            assertThat(e.details().get("max")).isNull();
        } else {
            assertThat(e.details()).containsEntry("max", max);
        }
    }

    @Test
    void definitionBasics() {
        assertThat(DEF.name()).isEqualTo("create_folder");
        assertThat(DEF.hints().readOnly()).isFalse();
        assertThat(DEF.hints().destructive()).isFalse();
        assertThat(DEF.hints().idempotent()).isTrue();
        assertThat(DEF.hints().openWorld()).isTrue();
        assertThat(DEF.inputSchema()).containsEntry("additionalProperties", false).containsEntry("required",
                List.of("account", "name"));
        assertThat(DEF.outputSchema()).containsKey("properties");
    }

    @Test
    @SuppressWarnings("unchecked")
    void outputSchemaFields() {
        Map<String, Object> props = (Map<String, Object>) DEF.outputSchema().get("properties");
        assertThat(props.keySet()).containsExactly("account", "folder", "parent", "delimiter", "created", "subscribed");
        assertThat((List<Object>) DEF.outputSchema().get("required"))
                .containsExactly("account", "folder", "parent", "delimiter", "created", "subscribed");
    }

    @Test
    void descriptionFollowsRules() {
        assertThat(DescriptionRules.violations(DEF)).isEmpty();
        assertThat(DEF.description()).contains(DescriptionRules.WRITE_SENTENCE)
                .contains("created=false").contains("does not delete folders");
    }

    @Test
    void validArgumentsParse() {
        CreateFolderTool.Parsed p = CreateFolderTool.parse(args("INBOX", "Projects"), "rid");
        assertThat(p.error()).isNull();
        assertThat(p.parent()).isEqualTo("INBOX");
        assertThat(p.name()).isEqualTo("Projects");
        assertThat(p.account()).isEqualTo("acc");
        CreateFolderTool.Parsed noParent = CreateFolderTool.parse(args(null, "A b"), "rid");
        assertThat(noParent.error()).isNull();
        assertThat(noParent.parent()).isNull();
    }

    @ParameterizedTest
    @ValueSource(strings = {"a\u0000b", "a\tb", "a\nb", "a\u007Fb", "a\u0085b", "a%b", "a*b", "abc\n", "abc\r\n",
            "abc\r", "abc\u0085", "abc\u2028", "abc\u2029"})
    void controlCharactersPercentAndStarRejectedBySchema(String name) {
        assertDetail(error(null, name), "name", "format", null);
    }

    @ParameterizedTest
    @ValueSource(strings = {" a", "a ", "\u00A0a", "a\u3000", ".", "..", "a  b"})
    void whitespaceAndDotNamesAreFormat(String name) {
        assertDetail(error(null, name), "name", "format", null);
    }

    @ParameterizedTest
    @ValueSource(strings = {"a\u202Eb", "a\u200Bb", "a\u00ADb", "a\u2066b", "a\uFEFFb"})
    void sanitizerChangedNamesAreFormat(String name) {
        assertDetail(error(null, name), "name", "format", null);
    }

    @Test
    void nameLengthBounds() {
        assertThat(CreateFolderTool.parse(args(null, "x".repeat(255)), "rid").error()).isNull();
        assertDetail(error(null, "x".repeat(256)), "name", "out_of_range", 255);
        assertDetail(error(null, ""), "name", "out_of_range", null);
    }

    @Test
    void parentBounds() {
        assertThat(CreateFolderTool.parse(args("p".repeat(1000), "n"), "rid").error()).isNull();
        assertDetail(error("p".repeat(1001), "n"), "parent", "out_of_range", 1000);
        assertDetail(error("", "n"), "parent", "out_of_range", null);
    }

    @Test
    void parentLongerThan1000Utf8BytesIsOutOfRange() {
        String parent = "\u00E4".repeat(501); // 501 characters, 1002 bytes
        assertDetail(error(parent, "n"), "parent", "out_of_range", 1000);
        assertThat(CreateFolderTool.parse(args("\u00E4".repeat(500), "n"), "rid").error()).isNull();
    }

    @Test
    void unknownAndMissingProperties() {
        Map<String, Object> m = args(null, "n");
        m.put("extra", "x");
        assertThat(CreateFolderTool.parse(m, "rid").error().details()).containsEntry("reason", "unknown_property");
        Map<String, Object> noName = args(null, null);
        assertThat(CreateFolderTool.parse(noName, "rid").error().details()).containsEntry("field", "name")
                .containsEntry("reason", "required");
    }
}
