package de.alexanderlindhorst.imapmcp.it;

import jakarta.mail.Folder;
import jakarta.mail.Message;
import jakarta.mail.MessagingException;
import jakarta.mail.Multipart;
import jakarta.mail.Session;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeBodyPart;
import jakarta.mail.internet.MimeMessage;
import jakarta.mail.internet.MimeMultipart;
import jakarta.mail.util.ByteArrayDataSource;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.List;
import java.util.Properties;
import org.eclipse.angus.mail.imap.AppendUID;
import org.eclipse.angus.mail.imap.IMAPFolder;
import org.eclipse.angus.mail.imap.IMAPStore;

/**
 * Seeds a mailbox over IMAP (APPEND) and reads it back, for any IMAPS server of the fixtures. Test-only: it trusts
 * every certificate because it only talks to throw-away containers. Returned UIDs are real IMAP UIDs.
 */
public final class MailSeeder implements AutoCloseable {

    /** An attachment of a multipart message. */
    public record Attachment(String filename, String contentType, byte[] data) {
    }

    private final Session session;
    private final IMAPStore store;

    public MailSeeder(String host, int port, String user, String password) throws MessagingException {
        Properties p = new Properties();
        p.put("mail.imaps.ssl.trust", "*");
        p.put("mail.imaps.ssl.checkserveridentity", "false");
        p.put("mail.imaps.peek", "true");
        p.put("mail.imaps.connectiontimeout", "10000");
        p.put("mail.imaps.timeout", "30000");
        session = Session.getInstance(p);
        store = (IMAPStore) session.getStore("imaps");
        store.connect(host, port, user, password);
    }

    public IMAPStore store() {
        return store;
    }

    public long appendPlain(String folder, String subject, String body) throws MessagingException {
        MimeMessage m = base(subject);
        m.setText(body, "UTF-8");
        return append(folder, m);
    }

    public long appendHtml(String folder, String subject, String html) throws MessagingException {
        MimeMessage m = base(subject);
        m.setContent(html, "text/html; charset=UTF-8");
        return append(folder, m);
    }

    /** multipart/mixed with a multipart/alternative (text and HTML) body part followed by the attachments. */
    public long appendMultipart(String folder, String subject, String text, String html,
            List<Attachment> attachments) throws MessagingException {
        MimeMessage m = base(subject);
        MimeMultipart alternative = new MimeMultipart("alternative");
        MimeBodyPart textPart = new MimeBodyPart();
        textPart.setText(text, "UTF-8");
        alternative.addBodyPart(textPart);
        MimeBodyPart htmlPart = new MimeBodyPart();
        htmlPart.setContent(html, "text/html; charset=UTF-8");
        alternative.addBodyPart(htmlPart);
        MimeBodyPart body = new MimeBodyPart();
        body.setContent(alternative);

        Multipart mixed = new MimeMultipart("mixed");
        mixed.addBodyPart(body);
        for (Attachment a : attachments) {
            MimeBodyPart part = new MimeBodyPart();
            part.setDataHandler(new jakarta.activation.DataHandler(new ByteArrayDataSource(a.data(), a.contentType())));
            part.setFileName(a.filename());
            part.setDisposition("attachment");
            mixed.addBodyPart(part);
        }
        m.setContent(mixed);
        return append(folder, m);
    }

    /**
     * APPENDs the bytes as they are (no re-encoding by Jakarta Mail), so a test controls the transfer encodings, 8bit
     * octets and truncated base64 the server stores. Needs UIDPLUS (APPENDUID).
     */
    public long appendRfc822(String folderName, byte[] rfc822) throws MessagingException {
        IMAPFolder f = (IMAPFolder) store.getFolder(folderName);
        if (!f.exists()) {
            f.create(Folder.HOLDS_MESSAGES);
        }
        org.eclipse.angus.mail.iap.Literal literal = new org.eclipse.angus.mail.iap.Literal() {
            @Override
            public int size() {
                return rfc822.length;
            }

            @Override
            public void writeTo(java.io.OutputStream out) throws IOException {
                out.write(rfc822);
            }
        };
        Object uid = f.doCommand(p -> p.appenduid(folderName, null, null, literal));
        if (!(uid instanceof AppendUID a)) {
            throw new MessagingException("server did not report the APPENDUID (UIDPLUS) for " + folderName);
        }
        return a.uid;
    }

    /** The complete RFC 822 source of a message (fetched without setting \Seen). */
    public String fetchRaw(String folder, long uid) throws MessagingException, IOException {
        IMAPFolder f = (IMAPFolder) store.getFolder(folder);
        f.open(Folder.READ_ONLY);
        try {
            Message m = f.getMessageByUID(uid);
            if (m == null) {
                throw new MessagingException("no message with UID " + uid + " in " + folder);
            }
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            m.writeTo(out);
            return out.toString(java.nio.charset.StandardCharsets.UTF_8);
        } finally {
            f.close(false);
        }
    }

    public int messageCount(String folder) throws MessagingException {
        IMAPFolder f = (IMAPFolder) store.getFolder(folder);
        f.open(Folder.READ_ONLY);
        try {
            return f.getMessageCount();
        } finally {
            f.close(false);
        }
    }

    private MimeMessage base(String subject) throws MessagingException {
        MimeMessage m = new MimeMessage(session);
        m.setFrom(new InternetAddress("sender@example.org"));
        m.setRecipient(Message.RecipientType.TO, new InternetAddress("recipient@example.org"));
        m.setSubject(subject, "UTF-8");
        return m;
    }

    private long append(String folderName, MimeMessage m) throws MessagingException {
        IMAPFolder f = (IMAPFolder) store.getFolder(folderName);
        if (!f.exists()) {
            f.create(Folder.HOLDS_MESSAGES);
        }
        AppendUID[] uids = f.appendUIDMessages(new Message[] {m});
        if (uids == null || uids.length != 1 || uids[0] == null) {
            throw new MessagingException("server did not report the APPENDUID (UIDPLUS) for " + folderName);
        }
        return uids[0].uid;
    }

    @Override
    public void close() throws MessagingException {
        store.close();
    }
}
