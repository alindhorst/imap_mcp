package de.alexanderlindhorst.imapmcp.core.imap;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.TreeSet;
import org.eclipse.angus.mail.imap.IMAPStore;

/**
 * Server capabilities that gate features (SPEC 5.4). Built from the post-authentication capability set.
 *
 * @param raw the probed capability names that the server advertises (upper case, immutable); Angus Mail's
 *            {@link IMAPStore} only answers per-name queries, so this is the subset of {@link #PROBED}
 */
public record ImapCapabilities(boolean move, boolean uidplus, boolean sort, boolean specialUse, boolean gmail,
                               Set<String> raw) {

    /** Capability names the server is asked about. */
    public static final List<String> PROBED = List.of("MOVE", "UIDPLUS", "SORT", "SPECIAL-USE", "X-GM-EXT-1");

    public ImapCapabilities {
        raw = Set.copyOf(new TreeSet<>(raw));
    }

    /** Reads the capabilities of a connected (authenticated) store; call right after login. */
    public static ImapCapabilities from(IMAPStore store) {
        try {
            List<String> present = new ArrayList<>();
            for (String name : PROBED) {
                if (store.hasCapability(name)) {
                    present.add(name);
                }
            }
            return fromNames(present);
        } catch (jakarta.mail.MessagingException e) {
            throw new IllegalStateException("cannot read IMAP capabilities", e);
        }
    }

    /** Builds the model from capability names, case-insensitively; unknown names are kept in {@code raw}. */
    public static ImapCapabilities fromNames(Collection<String> names) {
        Set<String> upper = new TreeSet<>();
        names.forEach(n -> upper.add(n.toUpperCase(Locale.ROOT)));
        return new ImapCapabilities(upper.contains("MOVE"), upper.contains("UIDPLUS"), upper.contains("SORT"),
                upper.contains("SPECIAL-USE"), upper.contains("X-GM-EXT-1"), upper);
    }
}
