package de.alexanderlindhorst.imapmcp.core.archfixture.writebans;

import jakarta.mail.Folder;
import jakarta.mail.MessagingException;

/** Violates rule (d): closes a folder outside ImapLease. Excluded from the real scan. */
public class FolderCloseFixture {
    void close(Folder folder) throws MessagingException {
        folder.close(false);
    }
}
