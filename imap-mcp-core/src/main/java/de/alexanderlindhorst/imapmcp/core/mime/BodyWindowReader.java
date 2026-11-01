package de.alexanderlindhorst.imapmcp.core.mime;

import jakarta.mail.MessagingException;
import org.eclipse.angus.mail.imap.IMAPFolder;

/** Reads a code-point window of a message body with partial BODY.PEEK fetches (SPEC 5.4). */
public final class BodyWindowReader {

    public static final long DEFAULT_MAX_BODY_PART_BYTES = 4L * 1024 * 1024;

    public BodyWindowReader() {
        this(DEFAULT_MAX_BODY_PART_BYTES);
    }

    public BodyWindowReader(long maxBodyPartBytes) {
    }

    public BodyWindow read(IMAPFolder openFolder, long uid, SelectedPart part, long offset, int maxChars)
            throws MessagingException {
        throw new UnsupportedOperationException("not implemented");
    }
}
