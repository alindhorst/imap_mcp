package de.alexanderlindhorst.imapmcp.core.search;

import de.alexanderlindhorst.imapmcp.core.content.UntrustedText;
import jakarta.mail.Flags;
import jakarta.mail.internet.InternetAddress;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Pure formatting of the pieces of a search_messages summary. Everything that originates from the message goes
 * through {@link UntrustedText}. Values are never logged.
 */
public final class SummaryFormat {

    public static final int MAX_RECIPIENTS = 10;
    static final int KEYWORD_MAX = 64;
    private static final int DATE_HEADER_MAX = 100;
    private static final Pattern COMMENT = Pattern.compile("\\([^()]*\\)");

    /** At most {@link #MAX_RECIPIENTS} rendered addresses and whether more were present. */
    public record AddressList(List<Map<String, Object>> entries, boolean truncated) {
    }

    private SummaryFormat() {
    }

    /**
     * Sent time with the offset of the Date header, RFC 3339; null when the header block has no parseable Date.
     * Takes the raw {@code BODY[HEADER.FIELDS (DATE)]} bytes as text.
     */
    public static String sentAt(String headerBlock) {
        if (headerBlock == null) {
            return null;
        }
        String unfolded = headerBlock.replace("\r\n", "\n").replaceAll("\n[ \t]+", " ");
        for (String line : unfolded.split("\n")) {
            if (line.regionMatches(true, 0, "Date:", 0, 5)) {
                return parseDate(line.substring(5));
            }
        }
        return null;
    }

    private static String parseDate(String value) {
        String v = COMMENT.matcher(value).replaceAll("").strip();
        if (v.length() > DATE_HEADER_MAX) {
            return null;
        }
        try {
            OffsetDateTime t = OffsetDateTime.parse(v, DateTimeFormatter.RFC_1123_DATE_TIME);
            return t.truncatedTo(ChronoUnit.SECONDS).format(DateTimeFormatter.ISO_OFFSET_DATE_TIME);
        } catch (DateTimeParseException e) {
            return null;
        }
    }

    /** IMAP spelling: system flags first in a fixed order, then user keywords sorted and sanitized. */
    public static List<String> flags(Flags flags) {
        List<String> out = new ArrayList<>();
        Flags.Flag[] system = flags.getSystemFlags();
        List<Flags.Flag> order = List.of(Flags.Flag.SEEN, Flags.Flag.ANSWERED, Flags.Flag.FLAGGED,
                Flags.Flag.DELETED, Flags.Flag.DRAFT, Flags.Flag.RECENT);
        for (Flags.Flag f : order) {
            if (Arrays.asList(system).contains(f)) {
                out.add(systemName(f));
            }
        }
        List<String> keywords = new ArrayList<>();
        for (String k : flags.getUserFlags()) {
            keywords.add(UntrustedText.singleLine(k, KEYWORD_MAX));
        }
        keywords.sort(null);
        out.addAll(keywords);
        return out;
    }

    private static String systemName(Flags.Flag f) {
        if (f == Flags.Flag.SEEN) {
            return "\\Seen";
        } else if (f == Flags.Flag.ANSWERED) {
            return "\\Answered";
        } else if (f == Flags.Flag.FLAGGED) {
            return "\\Flagged";
        } else if (f == Flags.Flag.DELETED) {
            return "\\Deleted";
        } else if (f == Flags.Flag.DRAFT) {
            return "\\Draft";
        }
        return "\\Recent";
    }

    /** {@code {name, address}}, both capped; name is null when the address has no display name. */
    public static Map<String, Object> address(InternetAddress a) {
        Map<String, Object> m = new LinkedHashMap<>();
        String name = a.getPersonal();
        m.put("name", name == null || name.isBlank() ? null
                : UntrustedText.singleLine(name, UntrustedText.DISPLAY_NAME_MAX));
        m.put("address", UntrustedText.singleLine(a.getAddress(), UntrustedText.ADDRESS_MAX));
        return m;
    }

    public static AddressList addresses(InternetAddress[] in) {
        if (in == null) {
            return new AddressList(List.of(), false);
        }
        List<Map<String, Object>> entries = new ArrayList<>();
        for (int i = 0; i < in.length && i < MAX_RECIPIENTS; i++) {
            entries.add(address(in[i]));
        }
        return new AddressList(entries, in.length > MAX_RECIPIENTS);
    }
}
