package de.alexanderlindhorst.imapmcp.core.archfixture.writebans;

import jakarta.mail.Folder;
import jakarta.mail.MessagingException;

/** Violates rule (a): opens a folder outside ImapLease. Excluded from the real scan. */
public class FolderOpenFixture {
    void open(Folder folder) throws MessagingException {
        folder.open(Folder.READ_ONLY);
    }
}
