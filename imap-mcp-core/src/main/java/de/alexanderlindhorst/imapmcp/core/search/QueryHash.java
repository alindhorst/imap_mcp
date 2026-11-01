package de.alexanderlindhorst.imapmcp.core.search;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.text.Normalizer;
import java.time.format.DateTimeFormatter;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * First 8 bytes of SHA-256 over the canonical JSON of the normalized criteria plus the resolved folder
 * full name. The JSON is written by hand (sorted keys, minimal escaping) so a library upgrade cannot change
 * hashes of cursors already handed out. page_size and cursor are not part of {@link SearchCriteria}.
 */
public final class QueryHash {

    public static final int LENGTH = 8;

    private QueryHash() {}

    public static byte[] of(SearchCriteria criteria, String resolvedFolder) {
        try {
            byte[] sha = MessageDigest.getInstance("SHA-256")
                    .digest(canonicalJson(criteria, resolvedFolder).getBytes(StandardCharsets.UTF_8));
            return Arrays.copyOf(sha, LENGTH);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    static String canonicalJson(SearchCriteria c, String resolvedFolder) {
        Map<String, String> fields = new TreeMap<>(); // key -> already rendered JSON value
        putString(fields, "folder", resolvedFolder);
        putString(fields, "from", c.from());
        putString(fields, "to", c.to());
        putString(fields, "subject", c.subject());
        putString(fields, "text", c.text());
        putString(fields, "gmail_raw", c.gmailRaw());
        if (c.dateFrom() != null) {
            putString(fields, "date_from", DateTimeFormatter.ISO_LOCAL_DATE.format(c.dateFrom()));
        }
        if (c.dateTo() != null) {
            putString(fields, "date_to", DateTimeFormatter.ISO_LOCAL_DATE.format(c.dateTo()));
        }
        if (c.since() != null) {
            fields.put("since", Long.toString(c.since().getEpochSecond()));
        }
        putBool(fields, "seen", c.seen());
        putBool(fields, "flagged", c.flagged());
        putBool(fields, "answered", c.answered());
        putBool(fields, "draft", c.draft());
        List<String> kw = c.keywords();
        if (kw != null && !kw.isEmpty()) {
            TreeSet<String> sorted = new TreeSet<>(); // natural String order = UTF-16 code unit order, stable
            kw.forEach(k -> sorted.add(normalize(k)));
            StringBuilder sb = new StringBuilder("[");
            for (String k : sorted) {
                if (sb.length() > 1) {
                    sb.append(',');
                }
                quote(sb, k);
            }
            fields.put("keywords", sb.append(']').toString());
        }
        StringBuilder out = new StringBuilder("{");
        fields.forEach((k, v) -> {
            if (out.length() > 1) {
                out.append(',');
            }
            quote(out, k);
            out.append(':').append(v);
        });
        return out.append('}').toString();
    }

    private static String normalize(String s) {
        return Normalizer.normalize(s.strip(), Normalizer.Form.NFC);
    }

    private static void putString(Map<String, String> fields, String key, String value) {
        if (value != null) {
            StringBuilder sb = new StringBuilder();
            quote(sb, normalize(value));
            fields.put(key, sb.toString());
        }
    }

    private static void putBool(Map<String, String> fields, String key, Boolean value) {
        if (value != null) {
            fields.put(key, value ? "true" : "false");
        }
    }

    /** Escapes only quote, backslash and control characters; everything else is literal. */
    private static void quote(StringBuilder sb, String s) {
        sb.append('"');
        for (int i = 0; i < s.length(); i++) {
            char ch = s.charAt(i);
            switch (ch) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\b' -> sb.append("\\b");
                case '\f' -> sb.append("\\f");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> {
                    if (ch < 0x20) {
                        sb.append(String.format("\\u%04x", (int) ch));
                    } else {
                        sb.append(ch);
                    }
                }
            }
        }
        sb.append('"');
    }
}
