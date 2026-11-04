package de.alexanderlindhorst.imapmcp.core.archfixture.writebans;

import org.eclipse.angus.mail.imap.IMAPFolder;
import jakarta.mail.Flags;
import jakarta.mail.Folder;
import jakarta.mail.Message;
import jakarta.mail.MessagingException;

/** Violates rule (c): one call per banned Angus/Jakarta mutation helper. Excluded from the real scan. */
public class MutationFixture {
    void mutate(IMAPFolder f, Folder other, Message m, Message[] ms) throws MessagingException {
        f.expunge();
        f.expunge(ms);
        f.setFlags(ms, new Flags(), true);
        m.setFlag(Flags.Flag.SEEN, true);
        m.setFlags(new Flags(), true);
        f.copyMessages(ms, other);
        f.moveMessages(ms, other);
        f.copyUIDMessages(ms, other);
        f.moveUIDMessages(ms, other);
        f.appendMessages(ms);
        f.addMessages(ms);
        f.appendUIDMessages(ms);
        f.create(Folder.HOLDS_MESSAGES);
        f.delete(false);
        f.renameTo(other);
    }
}
