package de.alexanderlindhorst.imapmcp.core.search;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Input schema of search_messages, restricted to what {@code InputValidator} supports. Constraints it cannot
 * express are checked by {@link SearchArguments#parse}. Property order is the validation order.
 */
public final class SearchSchema {

    /** IMAP atom characters only (no atom-specials, controls or non-ASCII); this also excludes a leading backslash. */
    static final String KEYWORD_PATTERN = "^[^\\x00-\\x20(){%*\"\\\\\\]\\x7f-\\uffff]+$";

    public static final List<String> FOLDER_ROLES =
            List.of("inbox", "sent", "drafts", "trash", "junk", "archive", "all", "flagged", "important");

    private SearchSchema() {}

    public static Map<String, Object> inputSchema() {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("account", Map.of("type", "string", "minLength", 1, "maxLength", 132));
        p.put("folder", Map.of("type", "string", "minLength", 1, "maxLength", 1000));
        p.put("folder_role", Map.of("type", "string", "enum", FOLDER_ROLES));
        for (String f : List.of("from", "to", "subject", "text")) {
            p.put(f, Map.of("type", "string", "minLength", 1, "maxLength", 256));
        }
        p.put("date_from", Map.of("type", "string", "format", "date"));
        p.put("date_to", Map.of("type", "string", "format", "date"));
        p.put("since", Map.of("type", "string", "format", "date-time"));
        for (String f : List.of("seen", "flagged", "answered", "draft")) {
            p.put(f, Map.of("type", "boolean"));
        }
        p.put("keywords", Map.of("type", "array", "maxItems", 10,
                "items", Map.of("type", "string", "minLength", 1, "maxLength", 64, "pattern", KEYWORD_PATTERN)));
        p.put("gmail_raw", Map.of("type", "string", "minLength", 1, "maxLength", 1024));
        p.put("page_size", Map.of("type", "integer", "minimum", 1, "maximum", 100, "default", 25));
        p.put("cursor", Map.of("type", "string", "minLength", 1, "maxLength", 256));
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", p);
        schema.put("required", List.of("account"));
        schema.put("additionalProperties", false);
        return schema;
    }
}
