package de.alexanderlindhorst.imapmcp.core.handle;

import java.util.Objects;

/**
 * What a message handle designates: folder full name, UIDVALIDITY and UID (both unsigned 32-bit, held as long;
 * 0 is not a valid IMAP value). Never a sequence number, never a mail object.
 */
public record MessageRef(String folder, long uidValidity, long uid) {

    public static final long MAX_U32 = 0xFFFFFFFFL;

    public MessageRef {
        Objects.requireNonNull(folder, "folder");
        if (folder.isEmpty()) {
            throw new IllegalArgumentException("folder must not be empty");
        }
        if (uidValidity < 1 || uidValidity > MAX_U32) {
            throw new IllegalArgumentException("uidValidity out of range 1..2^32-1");
        }
        if (uid < 1 || uid > MAX_U32) {
            throw new IllegalArgumentException("uid out of range 1..2^32-1");
        }
    }
}
