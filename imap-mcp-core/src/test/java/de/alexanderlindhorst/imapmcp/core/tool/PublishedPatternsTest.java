package de.alexanderlindhorst.imapmcp.core.tool;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import de.alexanderlindhorst.imapmcp.core.tools.GetMessageTool;
import de.alexanderlindhorst.imapmcp.core.tools.ListAccountsTool;
import de.alexanderlindhorst.imapmcp.core.tools.ListAttachmentsTool;
import de.alexanderlindhorst.imapmcp.core.tools.ListFoldersTool;
import de.alexanderlindhorst.imapmcp.core.tools.SearchMessagesTool;
import de.alexanderlindhorst.imapmcp.core.tools.write.CopyMessagesTool;
import de.alexanderlindhorst.imapmcp.core.tools.write.CreateFolderTool;
import de.alexanderlindhorst.imapmcp.core.tools.write.DeleteMessagesTool;
import de.alexanderlindhorst.imapmcp.core.tools.write.MoveMessagesTool;
import de.alexanderlindhorst.imapmcp.core.tools.write.SetFlagsTool;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** #25: guard over every published tool schema, so a future non-portable pattern fails here. */
class PublishedPatternsTest {

    private static List<ToolDefinition> all() {
        return List.of(ListAccountsTool.DEFINITION, ListFoldersTool.DEFINITION, SearchMessagesTool.DEFINITION,
                GetMessageTool.definition(100_000), ListAttachmentsTool.DEFINITION, SetFlagsTool.DEFINITION,
                MoveMessagesTool.DEFINITION, CopyMessagesTool.DEFINITION, DeleteMessagesTool.DEFINITION,
                CreateFolderTool.DEFINITION);
    }

    @SuppressWarnings("unchecked")
    private static void patterns(Object node, List<String> out) {
        if (node instanceof Map<?, ?> m) {
            for (Map.Entry<?, ?> e : m.entrySet()) {
                if ("pattern".equals(e.getKey()) && e.getValue() instanceof String s) {
                    out.add(s);
                } else {
                    patterns(e.getValue(), out);
                }
            }
        } else if (node instanceof Collection<?> c) {
            c.forEach(x -> patterns(x, out));
        }
    }

    @Test
    void everyPublishedPatternIsPortableEcma() {
        List<String> found = new ArrayList<>();
        for (ToolDefinition d : all()) {
            patterns(d.inputSchema(), found);
        }
        assertThat(found).hasSizeGreaterThanOrEqualTo(4);
        for (String p : found) {
            assertThat(p).doesNotContain("\\A").doesNotContain("\\z").doesNotContain("\\Z");
            assertThatCode(() -> EcmaPattern.check(p)).as(p).doesNotThrowAnyException();
        }
    }
}
