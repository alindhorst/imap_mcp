package de.alexanderlindhorst.imapmcp.core.mime;

import java.util.Locale;
import org.eclipse.angus.mail.imap.protocol.BODYSTRUCTURE;

/**
 * Finds one part of a fetched BODYSTRUCTURE by its section path with the same walk as {@link AttachmentClassifier}:
 * the same depth and part-count limits, {@code message/rfc822} not descended. Its filename and content type are
 * derived exactly as {@code list_attachments} derives them (SPEC 4, download_attachment step 7). Never fetches.
 */
public final class PartResolver {

    public sealed interface Result {
    }

    /** A leaf part (any leaf, also one that is no attachment). {@code filename} is nullable. */
    public record Leaf(String part, BODYSTRUCTURE structure, String filename, String contentType, String encoding,
            long encodedSize, boolean rfc822) implements Result {
    }

    /** {@code belowRfc822}: the path leads below a message/rfc822 part. */
    public record NotFound(boolean belowRfc822) implements Result {
    }

    public record NotALeaf() implements Result {
    }

    private final String target;
    private int visited;
    private BODYSTRUCTURE found;

    private PartResolver(String target) {
        this.target = target;
    }

    public static Result resolve(BODYSTRUCTURE root, String part) {
        BODYSTRUCTURE hit = find(root, part);
        if (hit != null) {
            return hit.isMulti() ? new NotALeaf() : leaf(part, hit);
        }
        // a proper prefix that is a message/rfc822 leaf means the path points into the encapsulated message
        for (int dot = part.indexOf('.'); dot >= 0; dot = part.indexOf('.', dot + 1)) {
            BODYSTRUCTURE prefix = find(root, part.substring(0, dot));
            if (prefix != null && !prefix.isMulti() && isRfc822(prefix)) {
                return new NotFound(true);
            }
        }
        return new NotFound(false);
    }

    private static BODYSTRUCTURE find(BODYSTRUCTURE root, String part) {
        if (root == null) {
            return null;
        }
        PartResolver r = new PartResolver(part);
        r.visit(root, root.isMulti() ? "" : "1", 0);
        return r.found;
    }

    private void visit(BODYSTRUCTURE node, String path, int depth) {
        if (found != null || depth > AttachmentClassifier.MAX_DEPTH || ++visited > AttachmentClassifier.MAX_PARTS) {
            return;
        }
        if (path.equals(target)) {
            found = node;
            return;
        }
        if (node.isMulti()) {
            BODYSTRUCTURE[] kids = node.bodies == null ? new BODYSTRUCTURE[0] : node.bodies;
            for (int i = 0; i < kids.length; i++) {
                visit(kids[i], path.isEmpty() ? String.valueOf(i + 1) : path + "." + (i + 1), depth + 1);
            }
        }
    }

    private static Leaf leaf(String part, BODYSTRUCTURE bs) {
        return new Leaf(part, bs, AttachmentClassifier.filename(bs), AttachmentClassifier.contentType(bs),
                bs.encoding == null ? null : bs.encoding.toLowerCase(Locale.ROOT), Math.max(bs.size, 0), isRfc822(bs));
    }

    private static boolean isRfc822(BODYSTRUCTURE bs) {
        return bs.type != null && "message".equalsIgnoreCase(bs.type) && "rfc822".equalsIgnoreCase(bs.subtype);
    }
}
