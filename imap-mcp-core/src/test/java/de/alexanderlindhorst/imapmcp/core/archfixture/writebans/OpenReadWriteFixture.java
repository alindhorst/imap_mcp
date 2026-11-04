package de.alexanderlindhorst.imapmcp.core.archfixture.writebans;

import de.alexanderlindhorst.imapmcp.core.imap.pool.ImapLease;
import jakarta.mail.MessagingException;

/** Violates rule (b): opens read-write from outside the write package. Excluded from the real scan. */
public class OpenReadWriteFixture {
    void open(ImapLease lease) throws MessagingException {
        lease.openReadWrite("INBOX");
    }
}
