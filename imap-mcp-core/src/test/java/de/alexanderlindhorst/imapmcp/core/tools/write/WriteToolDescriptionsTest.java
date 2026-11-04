package de.alexanderlindhorst.imapmcp.core.tools.write;

import static org.assertj.core.api.Assertions.assertThat;

import de.alexanderlindhorst.imapmcp.core.tool.DescriptionRules;
import de.alexanderlindhorst.imapmcp.core.tool.ToolDefinition;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/** SPEC 4 / 5.4: write tool descriptions name the Gmail label effects; no Gmail extension commands in v2. */
class WriteToolDescriptionsTest {

    private static final List<ToolDefinition> WRITE_DEFINITIONS = List.of(
            CopyMessagesTool.DEFINITION, MoveMessagesTool.DEFINITION, DeleteMessagesTool.DEFINITION,
            SetFlagsTool.DEFINITION, CreateFolderTool.DEFINITION);

    private static final Map<String, String> GMAIL_EFFECT = Map.of(
            CopyMessagesTool.NAME, "adds a label",
            MoveMessagesTool.NAME, "replaces the source label",
            DeleteMessagesTool.NAME, "removes all labels",
            SetFlagsTool.NAME, "across labels");

    @Test
    void everyWriteDescriptionHasWriteSentenceUndoStatementAndFitsTheLimit() {
        assertThat(WRITE_DEFINITIONS).hasSize(5);
        for (ToolDefinition def : WRITE_DEFINITIONS) {
            assertThat(def.description()).as(def.name()).contains(DescriptionRules.WRITE_SENTENCE);
            assertThat(def.description().toLowerCase()).as(def.name() + " undo statement").contains("undo");
            assertThat(def.description().length()).as(def.name() + " length")
                    .isLessThanOrEqualTo(DescriptionRules.MAX_LENGTH);
        }
    }

    @Test
    void copyMoveDeleteAndSetFlagsNameTheirGmailEffect() {
        for (ToolDefinition def : WRITE_DEFINITIONS) {
            String effect = GMAIL_EFFECT.get(def.name());
            if (effect != null) {
                assertThat(def.description()).as(def.name()).contains(effect);
            }
        }
    }

    @Test
    void noWriteSourceReferencesGmailExtensionItems() throws IOException {
        Path dir = Path.of("src/main/java/de/alexanderlindhorst/imapmcp/core/tools/write");
        List<String> hits = new ArrayList<>();
        try (Stream<Path> files = Files.list(dir)) {
            for (Path f : files.filter(p -> p.toString().endsWith(".java")).toList()) {
                String src = Files.readString(f);
                for (String banned : List.of("X-GM-LABELS", "X-GM-MSGID", "X-GM-THRID")) {
                    if (src.contains(banned)) {
                        hits.add(f.getFileName() + ": " + banned);
                    }
                }
            }
        }
        assertThat(hits).isEmpty();
    }
}
