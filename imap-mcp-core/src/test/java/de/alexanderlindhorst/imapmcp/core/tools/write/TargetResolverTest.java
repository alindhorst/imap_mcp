package de.alexanderlindhorst.imapmcp.core.tools.write;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import de.alexanderlindhorst.imapmcp.core.error.ErrorCode;
import de.alexanderlindhorst.imapmcp.core.error.ToolError;
import de.alexanderlindhorst.imapmcp.core.folder.FolderResolver;
import de.alexanderlindhorst.imapmcp.core.folder.FolderRole;
import de.alexanderlindhorst.imapmcp.core.imap.pool.ImapLease;
import jakarta.mail.Folder;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.eclipse.angus.mail.imap.IMAPFolder;
import org.eclipse.angus.mail.imap.IMAPStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class TargetResolverTest {

    private ImapLease lease;
    private IMAPStore store;
    private Folder root;
    private final List<IMAPFolder> listed = new ArrayList<>();

    @BeforeEach
    void setUp() throws Exception {
        lease = mock(ImapLease.class);
        store = mock(IMAPStore.class);
        root = mock(Folder.class);
        when(lease.store()).thenReturn(store);
        when(store.getDefaultFolder()).thenReturn(root);
        when(root.list("*")).thenAnswer(i -> listed.toArray(new Folder[0]));
    }

    private void listed(String fullName, String... attrs) throws Exception {
        IMAPFolder f = mock(IMAPFolder.class);
        when(f.getFullName()).thenReturn(fullName);
        when(f.getAttributes()).thenReturn(attrs);
        listed.add(f);
    }

    private void existing(String name, String... attrs) throws Exception {
        IMAPFolder f = mock(IMAPFolder.class);
        when(f.exists()).thenReturn(true);
        when(f.getAttributes()).thenReturn(attrs);
        when(store.getFolder(name)).thenReturn(f);
    }

    // argument shape

    @Test
    void neitherTargetIsRequiredOnTargetFolder() {
        ToolError e = TargetResolver.checkShape(null, null, "rid");
        assertThat(e.code()).isEqualTo(ErrorCode.INVALID_ARGUMENT);
        assertThat(e.details()).isEqualTo(ToolError.invalidArgument("target_folder", "required", null, "rid").details());
    }

    @Test
    void bothTargetsAreMutuallyExclusiveOnTargetFolderRole() {
        ToolError e = TargetResolver.checkShape("X", FolderRole.SENT, "rid");
        assertThat(e.details())
                .isEqualTo(ToolError.invalidArgument("target_folder_role", "mutually_exclusive", null, "rid").details());
    }

    @Test
    void exactlyOneTargetPassesShape() {
        assertThat(TargetResolver.checkShape("X", null, "rid")).isNull();
        assertThat(TargetResolver.checkShape(null, FolderRole.INBOX, "rid")).isNull();
    }

    @Test
    void verbatimTargetOverThousandBytesIsOutOfRangeWithoutIo() {
        assertThat(TargetResolver.checkShape("ä".repeat(500), null, "rid")).isNull(); // 1000 bytes
        ToolError e = TargetResolver.checkShape("ä".repeat(501), null, "rid");
        assertThat(e.details()).isEqualTo(ToolError.invalidArgument("target_folder", "out_of_range", 1000, "rid").details());
        verifyNoInteractions(lease);
    }

    @Test
    void resolveRejectsOverlongVerbatimBeforeAnyIo() throws Exception {
        FolderResolver.Resolution r = TargetResolver.resolve(lease, "ä".repeat(501), null, "rid");
        assertThat(r.error().code()).isEqualTo(ErrorCode.INVALID_ARGUMENT);
        verifyNoInteractions(lease);
    }

    // verbatim target

    @Test
    void existingSelectableFolderResolvesToFullName() throws Exception {
        existing("Work", "\\HasNoChildren");
        FolderResolver.Resolution r = TargetResolver.resolve(lease, "Work", null, "rid");
        assertThat(r.error()).isNull();
        assertThat(r.fullName()).isEqualTo("Work");
    }

    @Test
    void inboxIsNormalized() throws Exception {
        existing("inbox", "\\HasNoChildren");
        assertThat(TargetResolver.resolve(lease, "inbox", null, "rid").fullName()).isEqualTo("INBOX");
    }

    @Test
    void missingNoselectAndNonExistentAreFolderNotFound() throws Exception {
        IMAPFolder missing = mock(IMAPFolder.class);
        when(missing.exists()).thenReturn(false);
        when(store.getFolder("Gone")).thenReturn(missing);
        existing("Parent", "\\Noselect");
        existing("Ghost", "\\NonExistent");
        for (String n : List.of("Gone", "Parent", "Ghost")) {
            FolderResolver.Resolution r = TargetResolver.resolve(lease, n, null, "rid");
            assertThat(r.error().code()).isEqualTo(ErrorCode.FOLDER_NOT_FOUND);
            assertThat(r.error().details()).isEqualTo(Map.of("folder", n));
        }
    }

    // role target

    @Test
    void roleResolvesOnlyBySpecialUse() throws Exception {
        listed("Sent", "\\HasNoChildren"); // decoy
        listed("Gesendet", "\\Sent");
        assertThat(TargetResolver.resolve(lease, null, FolderRole.SENT, "rid").fullName()).isEqualTo("Gesendet");
    }

    @Test
    void missingRoleIsFolderNotFoundWithFolderRole() throws Exception {
        listed("Archive", "\\HasNoChildren");
        FolderResolver.Resolution r = TargetResolver.resolve(lease, null, FolderRole.ARCHIVE, "rid");
        assertThat(r.error().code()).isEqualTo(ErrorCode.FOLDER_NOT_FOUND);
        assertThat(r.error().details()).isEqualTo(Map.of("folder_role", "archive"));
    }

    @Test
    void roleOverThousandBytesIsUnsupportedFolderNameLength() throws Exception {
        listed("ä".repeat(501), "\\Archive");
        FolderResolver.Resolution r = TargetResolver.resolve(lease, null, FolderRole.ARCHIVE, "rid");
        assertThat(r.error().code()).isEqualTo(ErrorCode.UNSUPPORTED);
        assertThat(r.error().details()).isEqualTo(Map.of("capability", "folder_name_length"));
    }

    @Test
    void bothArgumentsReportTargetFolderRoleField() throws Exception {
        FolderResolver.Resolution r = TargetResolver.resolve(lease, "X", FolderRole.SENT, "rid");
        assertThat(r.error().details())
                .isEqualTo(ToolError.invalidArgument("target_folder_role", "mutually_exclusive", null, "rid").details());
    }

    // trash

    @Test
    void trashResolvesBySpecialUse() throws Exception {
        listed("Papierkorb", "\\Trash");
        assertThat(TargetResolver.resolveTrash(lease, "rid").fullName()).isEqualTo("Papierkorb");
    }

    @Test
    void missingTrashIsFolderNotFoundWithMoveMessagesHint() throws Exception {
        listed("Trash", "\\HasNoChildren"); // name heuristics are not allowed
        ToolError e = TargetResolver.resolveTrash(lease, "rid").error();
        assertThat(e.code()).isEqualTo(ErrorCode.FOLDER_NOT_FOUND);
        assertThat(e.details()).isEqualTo(Map.of("folder_role", "trash"));
        assertThat(e.hint()).contains("move_messages").contains("target_folder");
        assertThat(e.requestId()).isEqualTo("rid");
        verify(lease, never()).openReadOnly(anyString());
    }

    // same_folder for a role target

    @Test
    void roleTargetEqualToSourceIsSameFolderOnTargetFolderRole() {
        ToolError e = TargetResolver.sameFolderForRole("Sent", List.of("INBOX", "Sent"), "rid");
        assertThat(e.code()).isEqualTo(ErrorCode.INVALID_ARGUMENT);
        assertThat(e.details())
                .isEqualTo(ToolError.invalidArgument("target_folder_role", "same_folder", null, "rid").details());
    }

    @Test
    void roleTargetDifferentFromSourcesPasses() {
        assertThat(TargetResolver.sameFolderForRole("Sent", List.of("INBOX", "Work"), "rid")).isNull();
    }
}
