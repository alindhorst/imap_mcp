package de.alexanderlindhorst.imapmcp.core.mime;

import de.alexanderlindhorst.imapmcp.core.content.UntrustedText;
import de.alexanderlindhorst.imapmcp.core.mime.AttachmentList.Entry;
import de.alexanderlindhorst.imapmcp.core.mime.AttachmentList.Untrusted;
import jakarta.mail.internet.MimeUtility;
import jakarta.mail.internet.ParameterList;
import java.io.UnsupportedEncodingException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;
import org.eclipse.angus.mail.imap.protocol.BODYSTRUCTURE;

/** Classifies attachments from a fetched BODYSTRUCTURE; never fetches a body (SPEC 4, 5.4). */
public final class AttachmentClassifier {

    static final int MAX_ENTRIES = 100;
    static final int MAX_DEPTH = 32;
    static final int MAX_PARTS = 500;

    private static final Pattern TOKEN = Pattern.compile("[a-z0-9!#$&^_.+-]+");
    private static final String FALLBACK_TYPE = "application/octet-stream";

    private final String selected;
    private final List<Entry> entries = new ArrayList<>();
    private int total;
    private int visited;
    private boolean nonInline;

    private AttachmentClassifier(String selected) {
        this.selected = selected;
    }

    /** @param selectedBodyPartPath IMAP section path of the part used as message body (never counted), or null */
    public static AttachmentList classify(BODYSTRUCTURE bs, String selectedBodyPartPath) {
        AttachmentClassifier c = new AttachmentClassifier(selectedBodyPartPath);
        if (bs != null) {
            c.visit(bs, bs.isMulti() ? "" : "1", null, 0);
        }
        return new AttachmentList(List.copyOf(c.entries), c.total, c.total > c.entries.size(), c.nonInline);
    }

    public static boolean hasAttachments(BODYSTRUCTURE bs, String selectedBodyPartPath) {
        return classify(bs, selectedBodyPartPath).hasAttachments();
    }

    private void visit(BODYSTRUCTURE part, String path, BODYSTRUCTURE parent, int depth) {
        if (depth > MAX_DEPTH || ++visited > MAX_PARTS) {
            return;
        }
        if (part.isMulti()) {
            BODYSTRUCTURE[] kids = part.bodies == null ? new BODYSTRUCTURE[0] : part.bodies;
            for (int i = 0; i < kids.length; i++) {
                visit(kids[i], path.isEmpty() ? String.valueOf(i + 1) : path + "." + (i + 1), part, depth + 1);
            }
            return;
        }
        if (path.equals(selected)) {
            return;
        }
        String filename = filename(part);
        String disposition = part.disposition == null ? null : part.disposition.toLowerCase(Locale.ROOT);
        boolean attachmentDisposition = "attachment".equals(disposition);
        String type = part.type == null ? "" : part.type.toLowerCase(Locale.ROOT);
        boolean rfc822 = "message".equals(type) && "rfc822".equalsIgnoreCase(part.subtype);
        boolean inMixed = parent != null && "mixed".equalsIgnoreCase(parent.subtype);
        boolean inRelated = parent != null && "related".equalsIgnoreCase(parent.subtype);

        if (!(rfc822 || attachmentDisposition || filename != null || (inMixed && !"text".equals(type)))) {
            return;
        }
        boolean inline = !attachmentDisposition
                && ("inline".equals(disposition) || (inRelated && part.id != null));
        total++;
        if (!inline) {
            nonInline = true;
        }
        if (entries.size() < MAX_ENTRIES) {
            entries.add(new Entry(path, size(part), inline, new Untrusted(filename, contentType(part))));
        }
    }

    /** Approximate decoded size: base64 encoded octets * 3/4, otherwise the encoded octets. */
    private static long size(BODYSTRUCTURE part) {
        long size = Math.max(part.size, 0);
        return "base64".equalsIgnoreCase(part.encoding) ? size * 3 / 4 : size;
    }

    private static String contentType(BODYSTRUCTURE part) {
        String type = part.type == null ? "" : part.type.toLowerCase(Locale.ROOT);
        String sub = part.subtype == null ? "" : part.subtype.toLowerCase(Locale.ROOT);
        return TOKEN.matcher(type).matches() && TOKEN.matcher(sub).matches() ? type + "/" + sub : FALLBACK_TYPE;
    }

    private static String filename(BODYSTRUCTURE part) {
        String raw = param(part.dParams, "filename");
        if (raw == null) {
            raw = param(part.cParams, "name");
        }
        if (raw == null) {
            return null;
        }
        String decoded = raw;
        try {
            decoded = MimeUtility.decodeText(raw); // RFC 2047; RFC 2231 is decoded by ParameterList
        } catch (UnsupportedEncodingException e) {
            // keep the undecoded text
        }
        int cut = Math.max(decoded.lastIndexOf('/'), decoded.lastIndexOf('\\'));
        String name = UntrustedText.singleLine(decoded.substring(cut + 1), UntrustedText.FILENAME_MAX).strip();
        return name.isEmpty() ? null : name;
    }

    private static String param(ParameterList list, String name) {
        return list == null ? null : list.get(name);
    }
}
