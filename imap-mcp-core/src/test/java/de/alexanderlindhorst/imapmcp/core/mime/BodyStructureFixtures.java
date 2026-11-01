package de.alexanderlindhorst.imapmcp.core.mime;

import com.icegreen.greenmail.junit5.GreenMailExtension;
import com.icegreen.greenmail.user.GreenMailUser;
import jakarta.activation.DataHandler;
import jakarta.mail.Folder;
import jakarta.mail.Message;
import jakarta.mail.Session;
import jakarta.mail.Store;
import jakarta.mail.internet.MimeBodyPart;
import jakarta.mail.internet.MimeMessage;
import jakarta.mail.internet.MimeMultipart;
import jakarta.mail.util.ByteArrayDataSource;
import java.util.Properties;
import org.eclipse.angus.mail.imap.IMAPFolder;
import org.eclipse.angus.mail.imap.protocol.BODYSTRUCTURE;

/** Test fixtures: build MIME messages and fetch their real BODYSTRUCTURE from GreenMail by UID. */
final class BodyStructureFixtures {

    private static final Session MAIL = Session.getInstance(new Properties());

    private BodyStructureFixtures() {
    }

    static MimeBodyPart text(String subtype, String content) throws Exception {
        MimeBodyPart p = new MimeBodyPart();
        p.setText(content, "UTF-8", subtype);
        return p;
    }

    static MimeBodyPart binary(String type, byte[] data, String disposition) throws Exception {
        MimeBodyPart p = new MimeBodyPart();
        p.setDataHandler(new DataHandler(new ByteArrayDataSource(data, type)));
        if (disposition != null) {
            p.setHeader("Content-Disposition", disposition);
        }
        return p;
    }

    static MimeMessage message(MimeMultipart mp) throws Exception {
        MimeMessage m = new MimeMessage(MAIL);
        m.setFrom("a@localhost");
        m.setRecipients(Message.RecipientType.TO, "user@localhost");
        m.setSubject("fixture");
        m.setContent(mp);
        m.saveChanges();
        return m;
    }

    /** Delivers {@code msg} as the only message of the user's INBOX and fetches its BODYSTRUCTURE (no body). */
    static BODYSTRUCTURE fetch(GreenMailExtension greenMail, GreenMailUser user, MimeMessage msg) throws Exception {
        user.deliver(msg);
        Properties props = new Properties();
        props.put("mail.imaps.ssl.trust", "*");
        props.put("mail.imaps.ssl.checkserveridentity", "false");
        try (Store store = Session.getInstance(props).getStore("imaps")) {
            store.connect("127.0.0.1", greenMail.getImaps().getPort(), "user", "secret");
            IMAPFolder inbox = (IMAPFolder) store.getFolder("INBOX");
            inbox.open(Folder.READ_ONLY);
            try {
                long uid = inbox.getUID(inbox.getMessage(1));
                int seq = inbox.getMessageByUID(uid).getMessageNumber();
                return (BODYSTRUCTURE) inbox.doCommand(p -> p.fetchBodyStructure(seq));
            } finally {
                inbox.close(false);
            }
        }
    }
}
