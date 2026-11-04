package de.alexanderlindhorst.imapmcp.it;

import jakarta.mail.FetchProfile;
import jakarta.mail.Flags;
import jakarta.mail.Folder;
import jakarta.mail.Message;
import jakarta.mail.MessagingException;
import jakarta.mail.UIDFolder;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import org.eclipse.angus.mail.imap.IMAPFolder;
import org.eclipse.angus.mail.imap.IMAPStore;

/**
 * UIDs and flags per folder plus UIDVALIDITY, read over a raw IMAP connection (see {@link MailSeeder}); the evidence
 * of the no-side-effect and write ITs. {@code \Recent} is ignored because it depends on the session that looks.
 * {@link #diff} is pure and unit-tested without Docker.
 */
public final class MailboxSnapshot {

    /** One folder: its UIDVALIDITY and the flags of every UID. */
    public record FolderState(long uidValidity, Map<Long, Set<String>> flags) {
        public FolderState {
            TreeMap<Long, Set<String>> copy = new TreeMap<>();
            flags.forEach((uid, f) -> copy.put(uid, Set.copyOf(f)));
            flags = Collections.unmodifiableMap(copy);
        }
    }

    /** The difference between two snapshots; folders without any difference are not listed. */
    public record Diff(Map<String, Set<Long>> added, Map<String, Set<Long>> removed,
                       Map<String, Set<Long>> flagChanged, Set<String> uidValidityChanged) {

        public boolean isEmpty() {
            return added.isEmpty() && removed.isEmpty() && flagChanged.isEmpty() && uidValidityChanged.isEmpty();
        }

        public Set<Long> added(String folder) {
            return added.getOrDefault(folder, Set.of());
        }

        public Set<Long> removed(String folder) {
            return removed.getOrDefault(folder, Set.of());
        }

        public Set<Long> flagChanged(String folder) {
            return flagChanged.getOrDefault(folder, Set.of());
        }

        public boolean uidValidityChanged(String folder) {
            return uidValidityChanged.contains(folder);
        }
    }

    private final Map<String, FolderState> folders;

    private MailboxSnapshot(Map<String, FolderState> folders) {
        this.folders = Map.copyOf(folders);
    }

    /** A snapshot from known data (unit tests). */
    public static MailboxSnapshot of(Map<String, FolderState> folders) {
        return new MailboxSnapshot(folders);
    }

    /** Reads the given folders read-only (EXAMINE) without touching any flag. */
    public static MailboxSnapshot of(IMAPStore store, String... folderNames) throws MessagingException {
        Objects.requireNonNull(store, "store");
        Map<String, FolderState> out = new LinkedHashMap<>();
        for (String name : folderNames) {
            IMAPFolder f = (IMAPFolder) store.getFolder(name);
            f.open(Folder.READ_ONLY);
            try {
                Message[] messages = f.getMessages();
                FetchProfile fp = new FetchProfile();
                fp.add(UIDFolder.FetchProfileItem.UID);
                fp.add(FetchProfile.Item.FLAGS);
                f.fetch(messages, fp);
                Map<Long, Set<String>> flags = new TreeMap<>();
                for (Message m : messages) {
                    flags.put(f.getUID(m), names(m.getFlags()));
                }
                out.put(name, new FolderState(f.getUIDValidity(), flags));
            } finally {
                f.close(false);
            }
        }
        return new MailboxSnapshot(out);
    }

    public FolderState folder(String name) {
        return folders.get(name);
    }

    /** What changed from {@code before} to {@code after}, per folder of either snapshot. */
    public static Diff diff(MailboxSnapshot before, MailboxSnapshot after) {
        Map<String, Set<Long>> added = new TreeMap<>();
        Map<String, Set<Long>> removed = new TreeMap<>();
        Map<String, Set<Long>> changed = new TreeMap<>();
        Set<String> validity = new TreeSet<>();
        Set<String> names = new TreeSet<>(before.folders.keySet());
        names.addAll(after.folders.keySet());
        for (String name : names) {
            FolderState b = before.folders.get(name);
            FolderState a = after.folders.get(name);
            Map<Long, Set<String>> bf = b == null ? Map.of() : b.flags();
            Map<Long, Set<String>> af = a == null ? Map.of() : a.flags();
            if (b != null && a != null && b.uidValidity() != a.uidValidity()) {
                validity.add(name);
            }
            put(added, name, minus(af.keySet(), bf.keySet()));
            put(removed, name, minus(bf.keySet(), af.keySet()));
            Set<Long> c = new TreeSet<>();
            bf.forEach((uid, flags) -> {
                if (af.containsKey(uid) && !af.get(uid).equals(flags)) {
                    c.add(uid);
                }
            });
            put(changed, name, c);
        }
        return new Diff(added, removed, changed, validity);
    }

    private static Set<Long> minus(Set<Long> a, Set<Long> b) {
        Set<Long> r = new TreeSet<>(a);
        r.removeAll(b);
        return r;
    }

    private static void put(Map<String, Set<Long>> target, String folder, Set<Long> uids) {
        if (!uids.isEmpty()) {
            target.put(folder, uids);
        }
    }

    private static Set<String> names(Flags flags) {
        Set<String> out = new TreeSet<>();
        for (Flags.Flag f : flags.getSystemFlags()) {
            if (f == Flags.Flag.ANSWERED) {
                out.add("\\Answered");
            } else if (f == Flags.Flag.DELETED) {
                out.add("\\Deleted");
            } else if (f == Flags.Flag.DRAFT) {
                out.add("\\Draft");
            } else if (f == Flags.Flag.FLAGGED) {
                out.add("\\Flagged");
            } else if (f == Flags.Flag.SEEN) {
                out.add("\\Seen");
            }
        }
        out.addAll(java.util.List.of(flags.getUserFlags()));
        return out;
    }
}
