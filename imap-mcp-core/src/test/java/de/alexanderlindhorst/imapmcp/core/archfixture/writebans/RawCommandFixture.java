package de.alexanderlindhorst.imapmcp.core.archfixture.writebans;

import org.eclipse.angus.mail.iap.Protocol;
import org.eclipse.angus.mail.imap.IMAPFolder;
import jakarta.mail.MessagingException;

/** Violates rule (e): raw IMAP commands outside the command class. Excluded from the real scan. */
public class RawCommandFixture {
    void doCommand(IMAPFolder folder) throws MessagingException {
        folder.doCommand(p -> null);
    }

    void protocolCommand(Protocol p) throws Exception {
        p.command("UID STORE 1 +FLAGS (\\Deleted)", null);
    }
}
