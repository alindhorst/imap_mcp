package de.alexanderlindhorst.imapmcp.it;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class MailboxSnapshotTest {

    private static MailboxSnapshot snap(long validity, Map<Long, Set<String>> flags) {
        return MailboxSnapshot.of(Map.of("INBOX", new MailboxSnapshot.FolderState(validity, flags)));
    }

    @Test
    void identicalSnapshotsHaveAnEmptyDiff() {
        MailboxSnapshot a = snap(7, Map.of(1L, Set.of("\\Seen"), 2L, Set.of()));
        MailboxSnapshot b = snap(7, Map.of(1L, Set.of("\\Seen"), 2L, Set.of()));

        assertThat(MailboxSnapshot.diff(a, b).isEmpty()).isTrue();
    }

    @Test
    void reportsExactlyTheAddedRemovedAndFlagChangedUids() {
        MailboxSnapshot before = snap(7, Map.of(1L, Set.of(), 2L, Set.of("\\Seen"), 3L, Set.of("\\Flagged")));
        MailboxSnapshot after = snap(7, Map.of(2L, Set.of("\\Seen", "\\Deleted"), 3L, Set.of("\\Flagged"),
                4L, Set.of()));

        MailboxSnapshot.Diff d = MailboxSnapshot.diff(before, after);

        assertThat(d.isEmpty()).isFalse();
        assertThat(d.added("INBOX")).containsExactly(4L);
        assertThat(d.removed("INBOX")).containsExactly(1L);
        assertThat(d.flagChanged("INBOX")).containsExactly(2L);
        assertThat(d.uidValidityChanged("INBOX")).isFalse();
    }

    @Test
    void reportsAUidValidityChange() {
        MailboxSnapshot d0 = snap(7, Map.of(1L, Set.of()));
        MailboxSnapshot d1 = snap(8, Map.of(1L, Set.of()));

        MailboxSnapshot.Diff d = MailboxSnapshot.diff(d0, d1);

        assertThat(d.uidValidityChanged("INBOX")).isTrue();
        assertThat(d.isEmpty()).isFalse();
    }

    @Test
    void aFolderOnlyInOneSnapshotCountsAllItsUidsAsAddedOrRemoved() {
        MailboxSnapshot before = MailboxSnapshot.of(Map.of());
        MailboxSnapshot after = snap(7, Map.of(1L, Set.of(), 2L, Set.of()));

        assertThat(MailboxSnapshot.diff(before, after).added("INBOX")).containsExactlyInAnyOrder(1L, 2L);
        assertThat(MailboxSnapshot.diff(after, before).removed("INBOX")).containsExactlyInAnyOrder(1L, 2L);
    }

    @Test
    void unchangedFoldersAreReportedAsEmptySets() {
        MailboxSnapshot a = snap(7, Map.of(1L, Set.of()));

        MailboxSnapshot.Diff d = MailboxSnapshot.diff(a, a);

        assertThat(d.added("Other")).isEmpty();
        assertThat(d.flagChanged("INBOX")).isEmpty();
    }

    @Test
    void ofRequiresAtLeastOneFolderForTheLiveVariant() {
        assertThatThrownBy(() -> MailboxSnapshot.of(null, new String[0])).isInstanceOf(NullPointerException.class);
    }
}
