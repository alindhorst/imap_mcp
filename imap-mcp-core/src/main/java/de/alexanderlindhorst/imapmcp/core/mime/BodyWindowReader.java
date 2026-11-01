package de.alexanderlindhorst.imapmcp.core.mime;

import de.alexanderlindhorst.imapmcp.core.content.UntrustedText;
import jakarta.mail.Message;
import jakarta.mail.MessagingException;
import jakarta.mail.internet.MimeUtility;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.eclipse.angus.mail.imap.IMAPFolder;
import org.eclipse.angus.mail.imap.protocol.BODY;

/**
 * Reads a window of a message body part with partial {@code BODY.PEEK[section]<0.N>} fetches only (SPEC 5.4); the
 * body is never fetched without PEEK, so {@code \Seen} is never set.
 *
 * <p>Offsets and lengths count Unicode code points of the normalized text ({@link UntrustedText#bodyText}); a window
 * never splits a code point. The text is a pure function of a fixed byte prefix of the part: whatever prefix
 * length a call happens to fetch, the decoded text is a prefix of the full decoded text (base64 is cut at 4-char
 * groups, quoted-printable at line ends, and for an incomplete prefix the last code point and a trailing CR are
 * dropped), so offsets are stable between calls. Parts larger than {@code maxBodyPartBytes} are only read up to that
 * many encoded bytes ({@code sourceTruncated}); text/html is always converted from the same
 * {@code min(size, maxBodyPartBytes)} prefix. Plain text is capped the same way.
 */
public final class BodyWindowReader {

    public static final long DEFAULT_MAX_BODY_PART_BYTES = 4L * 1024 * 1024;

    private static final int MARGIN = 1024;
    private static final int META_SCAN_BYTES = 4096;
    private static final Pattern META_CHARSET = Pattern.compile(
            "(?i)<meta\\b[^>]*?charset\\s*=\\s*[\"']?\\s*([A-Za-z0-9._:\\-]+)");

    private final long maxBodyPartBytes;

    public BodyWindowReader() {
        this(DEFAULT_MAX_BODY_PART_BYTES);
    }

    public BodyWindowReader(long maxBodyPartBytes) {
        if (maxBodyPartBytes <= 0) {
            throw new IllegalArgumentException("maxBodyPartBytes must be positive");
        }
        this.maxBodyPartBytes = maxBodyPartBytes;
    }

    /** {@code openFolder} must be open (READ_ONLY); {@code uid} is the only message handle used. */
    public BodyWindow read(IMAPFolder openFolder, long uid, SelectedPart part, long offset, int maxChars)
            throws MessagingException {
        if (offset < 0 || maxChars <= 0) {
            throw new IllegalArgumentException("offset must be >= 0 and maxChars > 0");
        }
        boolean html = "text/html".equals(part.contentType());
        String encoding = part.transferEncoding() == null ? "" : part.transferEncoding().toLowerCase(Locale.ROOT);
        long size = part.encodedSize();
        long limit = Math.min(size, maxBodyPartBytes);

        // The sequence number is resolved from the UID inside this call and never leaves it (IMAPProtocol.peekBody
        // only takes sequence numbers); it is not exposed, returned or persisted.
        Message msg = openFolder.getMessageByUID(uid);
        if (msg == null) {
            throw new MessagingException("message not found");
        }
        int seq = msg.getMessageNumber();

        long need = offset + maxChars + 1;
        long n = html ? limit : estimate(need, encoding, limit);
        String text = "";
        Charset charset = CharsetResolver.resolve(part.charsetParam());
        boolean complete = limit <= 0;
        boolean capped = false;
        while (limit > 0) {
            byte[] raw = fetch(openFolder, seq, part.sectionPath(), (int) n);
            complete = n >= size || raw.length < n;
            capped = !complete && n >= limit;
            byte[] decoded = decodeTransfer(complete ? raw : cut(raw, encoding), encoding);
            if (html) {
                charset = metaCharset(decoded).orElse(charset);
            }
            String s = new String(decoded, charset);
            if (!complete) {
                s = dropIncompleteTail(s);
            }
            text = html ? HtmlToText.convert(s) : UntrustedText.bodyText(s);
            if (html || complete || capped || text.codePointCount(0, text.length()) >= need) {
                break;
            }
            n = Math.min(limit, n * 2);
        }
        return window(part, html, charset, text, offset, maxChars, complete, capped);
    }

    private static long estimate(long need, String encoding, long limit) {
        if (need > limit) { // cannot be more characters than encoded bytes
            return limit;
        }
        long perChar = 4 * switch (encoding) {
            case "base64" -> 7; // 4/3 plus CRLF every 76 chars, rounded up: 1.4 = 7/5
            case "quoted-printable" -> 15;
            default -> 5;
        };
        return Math.min(limit, need * perChar / 5 + MARGIN);
    }

    private static byte[] fetch(IMAPFolder folder, int seq, String section, int length) throws MessagingException {
        Object result = folder.doCommand(p -> {
            BODY body = p.peekBody(seq, section, 0, length);
            return body == null || body.getByteArray() == null ? new byte[0] : body.getByteArray().getNewBytes();
        });
        return (byte[]) result;
    }

    /** Cuts an incomplete encoded prefix at a boundary where the transfer decoding is exact. */
    private static byte[] cut(byte[] raw, String encoding) {
        switch (encoding) {
            case "base64" -> {
                int chars = 0;
                int end = 0;
                for (int i = 0; i < raw.length; i++) {
                    if (isBase64Char(raw[i]) && ++chars % 4 == 0) {
                        end = i + 1;
                    }
                }
                return Arrays.copyOf(raw, end);
            }
            case "quoted-printable" -> {
                int end = raw.length;
                while (end > 0 && raw[end - 1] != '\n') {
                    end--;
                }
                return Arrays.copyOf(raw, end);
            }
            default -> {
                return raw;
            }
        }
    }

    private static boolean isBase64Char(byte b) {
        return (b >= 'A' && b <= 'Z') || (b >= 'a' && b <= 'z') || (b >= '0' && b <= '9') || b == '+' || b == '/'
                || b == '=';
    }

    private static byte[] decodeTransfer(byte[] raw, String encoding) {
        if (encoding.isEmpty() || encoding.equals("7bit") || encoding.equals("8bit") || encoding.equals("binary")) {
            return raw;
        }
        try {
            return MimeUtility.decode(new ByteArrayInputStream(raw), encoding).readAllBytes();
        } catch (MessagingException | IOException e) { // unknown encoding or corrupt data: use the bytes as they are
            return raw;
        }
    }

    /** Drops the possibly truncated last code point and a trailing CR (which may be half of a CRLF). */
    private static String dropIncompleteTail(String s) {
        if (s.isEmpty()) {
            return s;
        }
        int end = s.offsetByCodePoints(s.length(), -1);
        if (end > 0 && s.charAt(end - 1) == '\r') {
            end--;
        }
        return s.substring(0, end);
    }

    private static Optional<Charset> metaCharset(byte[] decoded) {
        String head = new String(decoded, 0, Math.min(decoded.length, META_SCAN_BYTES), StandardCharsets.ISO_8859_1);
        Matcher m = META_CHARSET.matcher(head);
        if (!m.find()) {
            return Optional.empty();
        }
        try {
            return Optional.of(Charset.forName(m.group(1)));
        } catch (IllegalArgumentException e) { // illegal name or unsupported charset
            return Optional.empty();
        }
    }

    private static BodyWindow window(SelectedPart part, boolean html, Charset charset, String text, long offset,
            int maxChars, boolean complete, boolean capped) {
        int total = text.codePointCount(0, text.length());
        long start = Math.min(offset, total);
        long end = Math.min(start + maxChars, total);
        String slice = text.substring(text.offsetByCodePoints(0, (int) start), text.offsetByCodePoints(0, (int) end));
        int returned = (int) (end - start);
        boolean truncated;
        Long next;
        if (complete) {
            truncated = end < total;
            next = truncated ? end : null;
        } else {
            truncated = true; // more source exists: either unfetched (end < total) or beyond the byte cap
            next = end < total ? end : null;
        }
        return new BodyWindow(part.contentType(), html, charset.name(), offset, returned, complete ? (long) total : null,
                slice, truncated, next, capped);
    }
}
