package de.alexanderlindhorst.imapmcp.core.mime;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import org.eclipse.angus.mail.imap.protocol.BODYSTRUCTURE;

/**
 * Picks the text part that serves as message body from a fetched BODYSTRUCTURE (no body fetch).
 * Section paths follow {@link AttachmentClassifier}: a non-multipart message is section "1"
 * ({@code BODY.PEEK[1]} is valid for single-part messages, RFC 3501), children of a multipart are "n" / "n.m".
 * Depth-first, never descends into message/rfc822; the first non-attachment text/plain or text/html leaf wins;
 * in multipart/alternative a non-empty text/plain is preferred, else text/html. Without a body fetch "empty" means an encoded size of at most 2 octets (a bare CRLF).
 */
public final class BodyPartSelector {

    static final long EMPTY_MAX_OCTETS = 2;

    private BodyPartSelector() {
    }

    public static Optional<SelectedPart> select(BODYSTRUCTURE bs) {
        if (bs == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(visit(bs, bs.isMulti() ? "" : "1", null, 0));
    }

    private static SelectedPart visit(BODYSTRUCTURE part, String path, BODYSTRUCTURE parent, int depth) {
        if (depth > AttachmentClassifier.MAX_DEPTH) {
            return null;
        }
        if (!part.isMulti()) {
            return leaf(part, path, parent);
        }
        BODYSTRUCTURE[] kids = part.bodies == null ? new BODYSTRUCTURE[0] : part.bodies;
        List<SelectedPart> found = new ArrayList<>();
        boolean alternative = "alternative".equalsIgnoreCase(part.subtype);
        for (int i = 0; i < kids.length; i++) {
            SelectedPart s = visit(kids[i], path.isEmpty() ? String.valueOf(i + 1) : path + "." + (i + 1), part,
                    depth + 1);
            if (s != null) {
                if (!alternative) {
                    return s;
                }
                found.add(s);
            }
        }
        return alternative ? preferred(found) : null;
    }

    private static SelectedPart preferred(List<SelectedPart> found) {
        return found.stream().filter(s -> "text/plain".equals(s.contentType()) && s.encodedSize() > EMPTY_MAX_OCTETS).findFirst()
                .or(() -> found.stream().filter(s -> "text/html".equals(s.contentType())).findFirst())
                .or(() -> found.stream().findFirst()).orElse(null);
    }

    private static SelectedPart leaf(BODYSTRUCTURE part, String path, BODYSTRUCTURE parent) {
        if (!"text".equalsIgnoreCase(part.type) || part.subtype == null) {
            return null;
        }
        String sub = part.subtype.toLowerCase(Locale.ROOT);
        if (!sub.equals("plain") && !sub.equals("html")) {
            return null;
        }
        if (AttachmentClassifier.isAttachment(part, parent)) {
            return null;
        }
        String charset = part.cParams == null ? null : part.cParams.get("charset");
        return new SelectedPart(path, "text/" + sub, part.encoding, charset, Math.max(part.size, 0));
    }
}
