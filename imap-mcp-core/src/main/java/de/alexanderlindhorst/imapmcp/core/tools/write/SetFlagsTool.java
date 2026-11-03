package de.alexanderlindhorst.imapmcp.core.tools.write;

import de.alexanderlindhorst.imapmcp.core.error.ToolError;
import de.alexanderlindhorst.imapmcp.core.tool.DescriptionRules;
import de.alexanderlindhorst.imapmcp.core.tool.ToolDefinition;
import de.alexanderlindhorst.imapmcp.core.tool.ToolHints;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * {@code set_flags} (SPEC 4, v2 write contract): definition and argument parsing. Execution is added separately.
 * {@link #parse} is the second validation step after {@code InputValidator} (code checks {@code reserved},
 * {@code duplicate}, {@code conflict}, {@code no_change}) and yields at most one add set and one remove set with
 * system flags and keywords merged. {@code \Deleted} can only be cleared, {@code \Recent} is never settable.
 */
public final class SetFlagsTool {

    public static final String NAME = "set_flags";

    public static final int MAX_HANDLES = 100;
    public static final int MAX_KEYWORDS = 10;

    /**
     * IMAP atom characters as for search_messages keywords, anchored with \A...\z: the validator uses find() and
     * {@code $} would admit a trailing line terminator, which must never reach UID STORE.
     */
    public static final String KEYWORD_PATTERN = "\\A[^\\x00-\\x20(){%*\"\\\\\\]\\x7f-\\uffff]+\\z";


    /** System flag fields in wire order (also the order inside the merged sets). */
    private static final List<String> FLAG_FIELDS = List.of("seen", "flagged", "answered", "draft");

    private static final Set<String> RESERVED = Set.of("seen", "answered", "flagged", "deleted", "draft", "recent");

    private static final String NO_CHANGE_HINT = "Set at least one of seen, flagged, answered, draft, deleted, "
            + "add_keywords or remove_keywords.";

    private static final String RESERVED_HINT = "Use the boolean fields for system flags; recent cannot be set.";

    private static final String CONFLICT_HINT = "A keyword cannot be in add_keywords and remove_keywords.";

    private static Map<String, Object> keywordList(String description) {
        return schema("type", "array", "maxItems", MAX_KEYWORDS, "uniqueItems", true, "description", description,
                "items", schema("type", "string", "minLength", 1, "maxLength", 64, "pattern",
                        KEYWORD_PATTERN));
    }

    private static Map<String, Object> inputSchema() {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("account", schema("type", "string", "minLength", 1, "maxLength", 132));
        p.put("handles", schema("type", "array", "minItems", 1, "maxItems", MAX_HANDLES, "uniqueItems", true,
                "items", schema("type", "string", "minLength", 1)));
        for (String f : FLAG_FIELDS) {
            p.put(f, schema("type", "boolean", "description",
                    "true adds the flag, false removes it, absent leaves it unchanged."));
        }
        p.put("deleted", schema("type", "boolean", "enum", List.of(false), "description",
                "Only false is accepted: clears the deleted mark. The deleted flag cannot be set."));
        p.put("add_keywords", keywordList("Keywords to add (case-insensitive, at most 10)."));
        p.put("remove_keywords", keywordList("Keywords to remove (case-insensitive, at most 10)."));
        return schema("type", "object", "properties", p, "required", List.of("account", "handles"),
                "additionalProperties", false);
    }

    private static final Map<String, Object> RESULT_ITEM = schema("type", "object",
            "properties", schema("handle", schema("type", "string"), "status", schema("type", "string"),
                    "reason", schema("type", List.of("string", "null")),
                    "error", schema("type", List.of("object", "null"))),
            "required", List.of("handle", "status", "reason", "error"));

    private static final Map<String, Object> OUTPUT_SCHEMA = schema("type", "object",
            "properties", schema("account", schema("type", "string"),
                    "summary", schema("type", "object"),
                    "results", schema("type", "array", "items", RESULT_ITEM),
                    "notes", schema("type", "array", "items", schema("type", "string"))),
            "required", List.of("account", "summary", "results", "notes"));

    public static final ToolDefinition DEFINITION = new ToolDefinition(NAME, "Set message flags",
            "Add or remove flags (seen, flagged, answered, draft) and keywords on up to 100 messages by handle; "
                    + "deleted can only be cleared. Undo by calling set_flags again with the opposite value. "
                    + "On Gmail, flags apply to the message across all labels. "
                    + DescriptionRules.WRITE_SENTENCE + ".",
            inputSchema(), OUTPUT_SCHEMA, ToolHints.WRITE_DESTRUCTIVE_IDEMPOTENT);

    /**
     * Valid: {@code add} and {@code remove} are the flag/keyword atoms to store (system flags as {@code \Seen}
     * etc., keywords as given), each possibly empty but not both. Invalid: {@code error} is INVALID_ARGUMENT.
     */
    public record Parsed(List<String> handles, List<String> add, List<String> remove, ToolError error) {
        public boolean valid() {
            return error == null;
        }
    }

    private SetFlagsTool() {
    }

    /** Expects arguments that already passed the generic validator (null-free copy). Values are never echoed. */
    @SuppressWarnings("unchecked")
    public static Parsed parse(Map<String, Object> args, String requestId) {
        List<String> add = new ArrayList<>();
        List<String> remove = new ArrayList<>();
        for (String f : FLAG_FIELDS) {
            if (args.get(f) instanceof Boolean b) {
                (b ? add : remove).add("\\" + Character.toUpperCase(f.charAt(0)) + f.substring(1));
            }
        }
        if (args.get("deleted") instanceof Boolean) { // schema enum: only false
            remove.add("\\Deleted");
        }
        List<String> addKw = args.get("add_keywords") == null ? List.of() : (List<String>) args.get("add_keywords");
        List<String> removeKw = args.get("remove_keywords") == null ? List.of()
                : (List<String>) args.get("remove_keywords");
        ToolError e = checkKeywords("add_keywords", addKw, requestId);
        if (e == null) {
            e = checkKeywords("remove_keywords", removeKw, requestId);
        }
        if (e == null) {
            Set<String> added = new java.util.HashSet<>();
            addKw.forEach(k -> added.add(k.toLowerCase(Locale.ROOT)));
            for (int i = 0; i < removeKw.size() && e == null; i++) {
                if (added.contains(removeKw.get(i).toLowerCase(Locale.ROOT))) {
                    e = ToolError.invalidArgumentHint("remove_keywords[" + i + "]", "conflict", CONFLICT_HINT,
                            requestId);
                }
            }
        }
        if (e == null && add.isEmpty() && remove.isEmpty() && addKw.isEmpty() && removeKw.isEmpty()) {
            e = ToolError.invalidArgumentHint("seen", "no_change", NO_CHANGE_HINT, requestId);
        }
        if (e != null) {
            return new Parsed(null, null, null, e);
        }
        add.addAll(addKw);
        remove.addAll(removeKw);
        return new Parsed(List.copyOf((List<String>) args.get("handles")), List.copyOf(add), List.copyOf(remove),
                null);
    }

    private static ToolError checkKeywords(String field, List<String> keywords, String requestId) {
        Set<String> seen = new java.util.HashSet<>();
        for (int i = 0; i < keywords.size(); i++) {
            String k = keywords.get(i).toLowerCase(Locale.ROOT);
            if (RESERVED.contains(k)) {
                return ToolError.invalidArgumentHint(field + "[" + i + "]", "reserved", RESERVED_HINT, requestId);
            }
            if (!seen.add(k)) {
                return ToolError.invalidArgument(field + "[" + i + "]", "duplicate", null, requestId);
            }
        }
        return null;
    }

    private static Map<String, Object> schema(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            m.put((String) kv[i], kv[i + 1]);
        }
        return m;
    }
}
