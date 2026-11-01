package de.alexanderlindhorst.imapmcp.core.mime;

import de.alexanderlindhorst.imapmcp.core.content.UntrustedText;
import jakarta.mail.internet.MimeUtility;
import java.io.UnsupportedEncodingException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Turns a raw RFC 5322 header block into sanitized fields for {@code get_message}: message order, unfolded,
 * RFC 2047-decoded (a value that cannot be decoded stays raw), control and bidi characters removed. Caps (SPEC 4):
 * {@value #MAX_FIELDS} fields, {@link UntrustedText#HEADER_VALUE_MAX} code points per value and
 * {@value #MAX_TOTAL_CHARS} code points over all names and values; hitting any of them sets {@code truncated}.
 */
public final class HeaderFields {

    public static final int MAX_FIELDS = 100;
    public static final int MAX_TOTAL_CHARS = 32_000;

    /** One header field; both parts are email-derived and sanitized. */
    public record Field(String name, String value) {
    }

    public record Result(List<Field> fields, boolean truncated) {
    }

    private HeaderFields() {
    }

    public static Result parse(byte[] raw) {
        String text = new String(raw, StandardCharsets.UTF_8);
        List<Field> fields = new ArrayList<>();
        boolean truncated = false;
        int total = 0;
        for (String[] f : split(text)) {
            if (fields.size() >= MAX_FIELDS) {
                truncated = true;
                break;
            }
            String name = UntrustedText.singleLine(f[0], UntrustedText.DISPLAY_NAME_MAX);
            String decoded = decode(f[1]);
            String value = UntrustedText.singleLine(decoded, UntrustedText.HEADER_VALUE_MAX);
            if (decoded.codePointCount(0, decoded.length()) > UntrustedText.HEADER_VALUE_MAX) {
                truncated = true;
            }
            int size = name.codePointCount(0, name.length()) + value.codePointCount(0, value.length());
            if (total + size > MAX_TOTAL_CHARS) {
                truncated = true;
                break;
            }
            total += size;
            fields.add(new Field(name, value));
        }
        return new Result(List.copyOf(fields), truncated);
    }

    /** Splits into (name, unfolded raw value) pairs; stops at the blank line that ends the header block. */
    private static List<String[]> split(String text) {
        List<String[]> out = new ArrayList<>();
        String name = null;
        StringBuilder value = new StringBuilder();
        for (String line : text.split("\r\n|\n|\r", -1)) {
            if (line.isEmpty()) {
                break;
            }
            if ((line.charAt(0) == ' ' || line.charAt(0) == '\t') && name != null) {
                value.append(line);
                continue;
            }
            if (name != null) {
                out.add(new String[] {name, value.toString().strip()});
                name = null;
            }
            int colon = line.indexOf(':');
            if (colon > 0) {
                name = line.substring(0, colon).strip();
                value = new StringBuilder(line.substring(colon + 1));
            }
        }
        if (name != null) {
            out.add(new String[] {name, value.toString().strip()});
        }
        return out;
    }

    private static String decode(String value) {
        try {
            return MimeUtility.decodeText(value);
        } catch (UnsupportedEncodingException | RuntimeException e) {
            return value;
        }
    }
}
