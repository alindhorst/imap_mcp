package de.alexanderlindhorst.imapmcp.core.search;

import de.alexanderlindhorst.imapmcp.core.error.ToolError;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

/**
 * Second validation step after {@code InputValidator}: the constraints the generic validator cannot express,
 * then the typed view. Expects arguments that already passed the validator. Error values are never echoed.
 * Folder or folder_role absent is not an error here (the tool decides the default).
 */
public final class SearchArguments {

    public static final int DEFAULT_PAGE_SIZE = 25;
    /** UTF-8 bytes of the decoded folder full name; same limit as the message handle codec. */
    public static final int MAX_FOLDER_BYTES = 1000;

    /** Valid: criteria and friends are set. Invalid: {@code error} is the INVALID_ARGUMENT result. */
    public record Parsed(SearchCriteria criteria, String folder, String folderRole, int pageSize, String cursor,
            ToolError error) {
        public boolean valid() {
            return error == null;
        }
    }

    private SearchArguments() {}

    public static Parsed parse(Map<String, Object> args, String requestId) {
        String folder = (String) args.get("folder");
        if (folder != null) {
            if (hasUnpairedSurrogate(folder)) {
                return fail("folder", "format", null, requestId);
            }
            if (folder.getBytes(StandardCharsets.UTF_8).length > MAX_FOLDER_BYTES) {
                return fail("folder", "out_of_range", MAX_FOLDER_BYTES, requestId);
            }
        }
        String folderRole = (String) args.get("folder_role");
        if (folder != null && folderRole != null) {
            return fail("folder_role", "conflict", null, requestId);
        }
        for (String f : List.of("from", "to", "subject", "text")) {
            if (args.get(f) instanceof String s && s.isBlank()) {
                return fail(f, "format", null, requestId);
            }
        }
        LocalDate from = date(args.get("date_from"));
        LocalDate to = date(args.get("date_to"));
        if (from != null && to != null && from.isAfter(to)) {
            return fail("date_from", "out_of_range", null, requestId);
        }
        Instant since = args.get("since") instanceof String s ? OffsetDateTime.parse(s).toInstant() : null;
        @SuppressWarnings("unchecked")
        List<String> keywords = (List<String>) args.get("keywords");
        SearchCriteria c = new SearchCriteria(strip(args.get("from")), strip(args.get("to")),
                strip(args.get("subject")), strip(args.get("text")), from, to, since, (Boolean) args.get("seen"),
                (Boolean) args.get("flagged"), (Boolean) args.get("answered"), (Boolean) args.get("draft"),
                keywords, (String) args.get("gmail_raw"));
        int pageSize = args.get("page_size") instanceof Number n ? n.intValue() : DEFAULT_PAGE_SIZE;
        return new Parsed(c, folder, folderRole, pageSize, (String) args.get("cursor"), null);
    }

    private static Parsed fail(String field, String reason, Integer max, String requestId) {
        return new Parsed(null, null, null, 0, null, ToolError.invalidArgument(field, reason, max, requestId));
    }

    private static String strip(Object o) {
        return o == null ? null : ((String) o).strip();
    }

    private static LocalDate date(Object o) {
        return o instanceof String s ? LocalDate.parse(s) : null;
    }

    private static boolean hasUnpairedSurrogate(String s) {
        for (int i = 0; i < s.length(); i++) {
            char ch = s.charAt(i);
            if (Character.isHighSurrogate(ch) && i + 1 < s.length() && Character.isLowSurrogate(s.charAt(i + 1))) {
                i++;
            } else if (Character.isSurrogate(ch)) {
                return true;
            }
        }
        return false;
    }
}
