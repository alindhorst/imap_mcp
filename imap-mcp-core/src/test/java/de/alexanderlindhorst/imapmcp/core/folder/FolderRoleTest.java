package de.alexanderlindhorst.imapmcp.core.folder;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import org.junit.jupiter.api.Test;

class FolderRoleTest {

    @Test
    void wireValuesAndRfc6154Attributes() {
        Map<FolderRole, String> expectedAttr = Map.of(
                FolderRole.SENT, "\\Sent", FolderRole.DRAFTS, "\\Drafts", FolderRole.TRASH, "\\Trash",
                FolderRole.JUNK, "\\Junk", FolderRole.ARCHIVE, "\\Archive", FolderRole.ALL, "\\All",
                FolderRole.FLAGGED, "\\Flagged", FolderRole.IMPORTANT, "\\Important");
        expectedAttr.forEach((role, attr) -> {
            assertThat(role.attribute()).isEqualTo(attr);
            assertThat(role.wire()).isEqualTo(attr.substring(1).toLowerCase());
            assertThat(FolderRole.fromWire(role.wire())).contains(role);
        });
        assertThat(FolderRole.INBOX.wire()).isEqualTo("inbox");
        assertThat(FolderRole.INBOX.attribute()).isNull();
        assertThat(FolderRole.values()).hasSize(9);
    }

    @Test
    void fromWireIsExactLowercase() {
        assertThat(FolderRole.fromWire("Sent")).isEmpty();
        assertThat(FolderRole.fromWire("\\Sent")).isEmpty();
        assertThat(FolderRole.fromWire(null)).isEmpty();
    }
}
