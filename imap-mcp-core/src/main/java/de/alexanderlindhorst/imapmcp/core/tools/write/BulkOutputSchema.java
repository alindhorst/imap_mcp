package de.alexanderlindhorst.imapmcp.core.tools.write;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Output-schema fragment shared by the bulk write tools; matches {@link BulkResult#toMap()}. */
public final class BulkOutputSchema {

    private BulkOutputSchema() {
    }

    private static Map<String, Object> m(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            m.put((String) kv[i], kv[i + 1]);
        }
        return m;
    }

    private static Map<String, Object> nullableString() {
        return m("type", List.of("string", "null"));
    }

    private static Map<String, Object> nullableEnum(String... values) {
        List<Object> e = new ArrayList<>(List.of(values));
        e.add(null);
        return m("type", List.of("string", "null"), "enum", e);
    }

    /** The v1 error object without request_id. */
    private static Map<String, Object> errorObject() {
        return m("type", List.of("object", "null"),
                "properties", m("code", m("type", "string"), "message", m("type", "string"),
                        "retryable", m("type", "boolean"), "hint", m("type", "string"),
                        "retry_after_ms", m("type", "integer"), "details", m("type", "object")),
                "required", List.of("code", "message", "retryable", "hint"));
    }

    public static Map<String, Object> of(BulkResult.Kind kind) {
        boolean target = kind != BulkResult.Kind.SET_FLAGS;
        Map<String, Object> itemProps = new LinkedHashMap<>();
        itemProps.put("handle", m("type", "string"));
        List<Object> statuses = new ArrayList<>();
        for (ItemStatus s : ItemStatus.values()) {
            statuses.add(s.wire());
        }
        itemProps.put("status", m("type", "string", "enum", statuses));
        if (target) {
            itemProps.put("new_handle", nullableString());
            itemProps.put("note", nullableEnum(BulkResult.NEW_UID_UNKNOWN));
        }
        itemProps.put("reason", nullableEnum("uidvalidity_changed", "folder_missing"));
        itemProps.put("error", errorObject());
        Map<String, Object> item = m("type", "object", "properties", itemProps,
                "required", new ArrayList<>(itemProps.keySet()), "additionalProperties", false);

        Map<String, Object> summaryProps = new LinkedHashMap<>();
        summaryProps.put("requested", m("type", "integer", "minimum", 0));
        for (ItemStatus s : ItemStatus.values()) {
            summaryProps.put(s.wire(), m("type", "integer", "minimum", 0));
        }

        Map<String, Object> props = new LinkedHashMap<>();
        props.put("account", m("type", "string"));
        if (target) {
            props.put("target_folder", m("type", "string"));
        }
        props.put("summary", m("type", "object", "properties", summaryProps,
                "required", new ArrayList<>(summaryProps.keySet()), "additionalProperties", false));
        props.put("results", m("type", "array", "items", item));
        props.put("notes", m("type", "array", "items", m("type", "string", "enum", BulkResult.NOTES)));
        return m("type", "object", "properties", props, "required", new ArrayList<>(props.keySet()),
                "additionalProperties", false);
    }
}
