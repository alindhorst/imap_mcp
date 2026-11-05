package de.alexanderlindhorst.imapmcp.core.download;

import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * The file name of a downloaded attachment (SPEC 5.4 "File name"): a fixed, table-tested sanitizer over the
 * already sanitized {@code untrusted.filename}, plus the numbered clash candidates. There is no file-type list (owner
 * decision). The value is email-derived and therefore never logged.
 */
public final class AttachmentFileName {

    private static final int MAX_BYTES = 200;
    private static final String ALLOWED_PUNCTUATION = ".-_+()[],@";
    private static final Set<String> RESERVED = Set.of("CON", "PRN", "AUX", "NUL", "COM1", "COM2", "COM3", "COM4",
            "COM5", "COM6", "COM7", "COM8", "COM9", "LPT1", "LPT2", "LPT3", "LPT4", "LPT5", "LPT6", "LPT7", "LPT8",
            "LPT9");
    private static final Pattern EXTENSION = Pattern.compile("[A-Za-z0-9]{1,16}");

    private final String stem;
    private final String extension;

    private AttachmentFileName(String stem, String extension) {
        this.stem = stem;
        this.extension = extension;
    }

    /**
     * @param untrustedFilename the filename as {@code list_attachments} reports it, or null
     * @param part              the section path (for the fallback name)
     * @param rfc822            true for a {@code message/rfc822} part (the fallback gets {@code .eml})
     */
    public static AttachmentFileName of(String untrustedFilename, String part, boolean rfc822) {
        String name = untrustedFilename == null ? "" : sanitize(untrustedFilename);
        if (name.isEmpty()) {
            return new AttachmentFileName("attachment-" + part.replace('.', '-'), rfc822 ? "eml" : null);
        }
        int dot = name.lastIndexOf('.');
        String stem = name;
        String ext = null;
        if (dot > 0 && EXTENSION.matcher(name.substring(dot + 1)).matches()) {
            stem = name.substring(0, dot);
            ext = name.substring(dot + 1);
        }
        int room = MAX_BYTES - (ext == null ? 0 : 1 + ext.length());
        if (utf8Length(stem) > room) {
            stem = trimEnd(cut(stem, room));
            if (stem.isEmpty()) {
                return new AttachmentFileName("attachment-" + part.replace('.', '-'), rfc822 ? "eml" : null);
            }
        }
        return new AttachmentFileName(stem, ext);
    }

    /** Test seam: a name that skipped the sanitizer, to provoke file system errors. */
    static AttachmentFileName rawForTest(String stem, String extension) {
        return new AttachmentFileName(stem, extension);
    }

    public String fileName() {
        return candidate(1);
    }

    /** Candidate {@code n}: 1 is the name itself, from 2 on {@code <stem> (n)<.ext>}. */
    public String candidate(int n) {
        String numbered = n <= 1 ? stem : stem + " (" + n + ")";
        return extension == null ? numbered : numbered + "." + extension;
    }

    private static String sanitize(String input) {
        String s = Normalizer.normalize(input, Normalizer.Form.NFC);
        StringBuilder b = new StringBuilder(s.length());
        s.codePoints().forEach(cp -> b.appendCodePoint(allowed(cp) ? cp : '_'));
        // runs of spaces become one
        String t = b.toString().replaceAll(" {2,}", " ");
        t = trimEnds(t);
        if (t.startsWith("-")) {
            t = "_" + t.substring(1);
        }
        int firstDot = t.indexOf('.');
        String head = firstDot < 0 ? t : t.substring(0, firstDot);
        if (RESERVED.contains(head.toUpperCase(Locale.ROOT))) {
            t = "_" + t;
        }
        return t;
    }

    private static boolean allowed(int cp) {
        if (cp == ' ' || (cp < 128 && ALLOWED_PUNCTUATION.indexOf(cp) >= 0)) {
            return true;
        }
        return switch (Character.getType(cp)) {
            case Character.UPPERCASE_LETTER, Character.LOWERCASE_LETTER, Character.TITLECASE_LETTER,
                    Character.MODIFIER_LETTER, Character.OTHER_LETTER, Character.NON_SPACING_MARK,
                    Character.ENCLOSING_MARK, Character.COMBINING_SPACING_MARK, Character.DECIMAL_DIGIT_NUMBER,
                    Character.LETTER_NUMBER, Character.OTHER_NUMBER -> true;
            default -> false;
        };
    }

    private static boolean spaceOrDot(char c) {
        return c == ' ' || c == '.';
    }

    private static String trimEnds(String s) {
        int from = 0;
        int to = s.length();
        while (from < to && spaceOrDot(s.charAt(from))) {
            from++;
        }
        while (to > from && spaceOrDot(s.charAt(to - 1))) {
            to--;
        }
        return s.substring(from, to);
    }

    private static String trimEnd(String s) {
        int to = s.length();
        while (to > 0 && spaceOrDot(s.charAt(to - 1))) {
            to--;
        }
        return s.substring(0, to);
    }

    private static int utf8Length(String s) {
        return s.getBytes(StandardCharsets.UTF_8).length;
    }

    /** The longest prefix of {@code s} (cut at a code point boundary) of at most {@code maxBytes} UTF-8 bytes. */
    private static String cut(String s, int maxBytes) {
        int bytes = 0;
        int i = 0;
        while (i < s.length()) {
            int cp = s.codePointAt(i);
            int n = new String(Character.toChars(cp)).getBytes(StandardCharsets.UTF_8).length;
            if (bytes + n > maxBytes) {
                break;
            }
            bytes += n;
            i += Character.charCount(cp);
        }
        return s.substring(0, i);
    }
}
