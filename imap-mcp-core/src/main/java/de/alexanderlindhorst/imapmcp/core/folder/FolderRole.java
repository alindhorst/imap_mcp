package de.alexanderlindhorst.imapmcp.core.folder;

import java.util.Optional;

/**
 * Special-use folder roles (SPEC 4, 5.4). Wire values are the lowercase RFC 6154 / RFC 8457 attribute names without
 * the backslash. {@code inbox} is the only role defined by name (RFC 3501 INBOX) and has no attribute.
 */
public enum FolderRole {
    INBOX("inbox", null),
    SENT("sent", "\\Sent"),
    DRAFTS("drafts", "\\Drafts"),
    TRASH("trash", "\\Trash"),
    JUNK("junk", "\\Junk"),
    ARCHIVE("archive", "\\Archive"),
    ALL("all", "\\All"),
    FLAGGED("flagged", "\\Flagged"),
    IMPORTANT("important", "\\Important");

    private final String wire;
    private final String attribute;

    FolderRole(String wire, String attribute) {
        this.wire = wire;
        this.attribute = attribute;
    }

    public String wire() {
        return wire;
    }

    /** The LIST attribute carrying this role; {@code null} for {@link #INBOX}. */
    public String attribute() {
        return attribute;
    }

    public static Optional<FolderRole> fromWire(String s) {
        for (FolderRole r : values()) {
            if (r.wire.equals(s)) {
                return Optional.of(r);
            }
        }
        return Optional.empty();
    }
}
