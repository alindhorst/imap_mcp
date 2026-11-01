package de.alexanderlindhorst.imapmcp.core.folder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.alexanderlindhorst.imapmcp.core.error.ErrorCode;
import de.alexanderlindhorst.imapmcp.core.error.ToolError;
import de.alexanderlindhorst.imapmcp.core.imap.pool.ImapLease;
import jakarta.mail.Folder;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.eclipse.angus.mail.imap.IMAPFolder;
import org.eclipse.angus.mail.imap.IMAPStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

class FolderResolverTest {

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

    private IMAPFolder listed(String fullName, String... attrs) throws Exception {
        IMAPFolder f = mock(IMAPFolder.class);
        when(f.getFullName()).thenReturn(fullName);
        when(f.getAttributes()).thenReturn(attrs);
        listed.add(f);
        return f;
    }

    private FolderResolver.Resolution role(FolderRole r) throws Exception {
        return FolderResolver.resolve(lease, null, r, "rid");
    }

    @ParameterizedTest
    @EnumSource(value = FolderRole.class, names = "INBOX", mode = EnumSource.Mode.EXCLUDE)
    void everyAttributeRoleResolvesByAttributeNotName(FolderRole r) throws Exception {
        listed("Other", "\\HasNoChildren");
        listed(r.wire(), "\\HasNoChildren"); // a decoy named like the role, without the attribute
        listed("Real-" + r.wire(), "\\HasNoChildren", r.attribute());
        FolderResolver.Resolution res = role(r);
        assertThat(res.error()).isNull();
        assertThat(res.fullName()).isEqualTo("Real-" + r.wire());
    }

    @Test
    void attributeMatchIsCaseInsensitive() throws Exception {
        listed("Gesendet", "\\SENT");
        assertThat(role(FolderRole.SENT).fullName()).isEqualTo("Gesendet");
    }

    @Test
    void inboxRoleIsByRfc3501NameWithoutList() throws Exception {
        assertThat(role(FolderRole.INBOX).fullName()).isEqualTo("INBOX");
        verify(root, never()).list(anyString());
    }

    @Test
    void roleWithoutAttributeIsFolderNotFoundEvenIfNamedSent() throws Exception {
        listed("Sent", "\\HasNoChildren");
        FolderResolver.Resolution res = role(FolderRole.SENT);
        assertThat(res.fullName()).isNull();
        assertThat(res.error().code()).isEqualTo(ErrorCode.FOLDER_NOT_FOUND);
        assertThat(res.error().details()).isEqualTo(Map.of("folder_role", "sent"));
        assertThat(res.error().hint()).isEqualTo("Call list_folders to see available roles.");
        assertThat(res.error().requestId()).isEqualTo("rid");
    }

    @Test
    void nullAttributesAreTolerated() throws Exception {
        listed("Sent", (String[]) null);
        assertThat(role(FolderRole.SENT).error().code()).isEqualTo(ErrorCode.FOLDER_NOT_FOUND);
    }

    @Test
    void noselectFolderCarryingTheRoleIsSkipped() throws Exception {
        listed("A", "\\Sent", "\\Noselect");
        listed("B", "\\Sent");
        assertThat(role(FolderRole.SENT).fullName()).isEqualTo("B");
    }

    @Test
    void severalFoldersWithSameAttributePickFirstInCodePointOrder() throws Exception {
        listed("😀", "\\Archive"); // U+1F600: after U+FF5E in code point order, before it in UTF-16 order
        listed("～", "\\Archive");
        listed("z", "\\Archive");
        assertThat(role(FolderRole.ARCHIVE).fullName()).isEqualTo("z");
        listed.clear();
        listed("😀", "\\Archive");
        listed("～", "\\Archive");
        assertThat(role(FolderRole.ARCHIVE).fullName()).isEqualTo("～");
    }

    @Test
    void roleFolderOverThousandBytesIsUnsupportedWithoutFolderDetail() throws Exception {
        String longName = "ä".repeat(501); // 1002 UTF-8 bytes
        listed(longName, "\\Sent");
        FolderResolver.Resolution res = role(FolderRole.SENT);
        assertThat(res.fullName()).isNull();
        ToolError e = res.error();
        assertThat(e.code()).isEqualTo(ErrorCode.UNSUPPORTED);
        assertThat(e.details()).isEqualTo(Map.of("capability", "folder_name_length"));
        assertThat(e.message()).isEqualTo("The folder's full name exceeds 1000 UTF-8 bytes; this server cannot address it.");
        verify(store, never()).getFolder(anyString());
        verify(lease, never()).openReadOnly(anyString());
    }

    @Test
    void roleFolderOfExactlyThousandBytesResolves() throws Exception {
        String name = "ä".repeat(500); // 1000 UTF-8 bytes
        listed(name, "\\Sent");
        assertThat(role(FolderRole.SENT).fullName()).isEqualTo(name);
    }

    @Test
    void bothParametersAreInvalidArgument() throws Exception {
        FolderResolver.Resolution res = FolderResolver.resolve(lease, "INBOX", FolderRole.SENT, "rid");
        assertThat(res.error().code()).isEqualTo(ErrorCode.INVALID_ARGUMENT);
        assertThat(res.error().details()).isEqualTo(ToolError.invalidArgument("folder_role", "mutually_exclusive", null, "rid").details());
        verify(lease, never()).store();
    }

    @Test
    void neitherParameterIsInbox() throws Exception {
        FolderResolver.Resolution res = FolderResolver.resolve(lease, null, null, "rid");
        assertThat(res.fullName()).isEqualTo("INBOX");
        verify(lease, never()).store();
    }

    @Test
    void folderMustExistAndBeSelectable() throws Exception {
        IMAPFolder ok = mock(IMAPFolder.class);
        when(ok.exists()).thenReturn(true);
        when(ok.getAttributes()).thenReturn(new String[] {"\\HasNoChildren"});
        when(store.getFolder("Work")).thenReturn(ok);
        assertThat(FolderResolver.resolve(lease, "Work", null, "rid").fullName()).isEqualTo("Work");

        IMAPFolder missing = mock(IMAPFolder.class);
        when(missing.exists()).thenReturn(false);
        when(store.getFolder("Nope")).thenReturn(missing);
        ToolError e = FolderResolver.resolve(lease, "Nope", null, "rid").error();
        assertThat(e.code()).isEqualTo(ErrorCode.FOLDER_NOT_FOUND);
        assertThat(e.details()).isEqualTo(Map.of("folder", "Nope"));
    }

    @Test
    void noselectAndNonExistentFoldersAreNotFound() throws Exception {
        for (String attr : List.of("\\Noselect", "\\NonExistent", "\\NOSELECT")) {
            IMAPFolder f = mock(IMAPFolder.class);
            when(f.exists()).thenReturn(true);
            when(f.getAttributes()).thenReturn(new String[] {"\\HasChildren", attr});
            when(store.getFolder("Parent")).thenReturn(f);
            ToolError e = FolderResolver.resolve(lease, "Parent", null, "rid").error();
            assertThat(e.code()).isEqualTo(ErrorCode.FOLDER_NOT_FOUND);
            assertThat(e.details()).isEqualTo(Map.of("folder", "Parent"));
        }
        verify(lease, never()).openReadOnly(anyString());
    }
}
