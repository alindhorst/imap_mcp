package de.alexanderlindhorst.imapmcp.core.tools;

import de.alexanderlindhorst.imapmcp.core.mime.AttachmentList;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** The attachment array shared by {@code list_attachments} and {@code get_message}: one schema, one mapping. */
final class AttachmentEntries {

    static final Map<String, Object> ENTRY_SCHEMA = Schemas.of("type", "object",
            "properties", Schemas.of("part", Schemas.of("type", "string"),
                    "size", Schemas.of("type", "integer"),
                    "inline", Schemas.of("type", "boolean"),
                    "untrusted", Schemas.of("type", "object",
                            "properties", Schemas.of("filename", Schemas.of("type", List.of("string", "null")),
                                    "content_type", Schemas.of("type", "string")),
                            "required", List.of("filename", "content_type"))),
            "required", List.of("part", "size", "inline", "untrusted"));

    static final Map<String, Object> ARRAY_SCHEMA = Schemas.of("type", "array", "items", ENTRY_SCHEMA);

    private AttachmentEntries() {
    }

    static List<Map<String, Object>> toJson(AttachmentList list) {
        List<Map<String, Object>> entries = new ArrayList<>();
        for (AttachmentList.Entry e : list.entries()) {
            Map<String, Object> untrusted = new LinkedHashMap<>();
            untrusted.put("filename", e.untrusted().filename());
            untrusted.put("content_type", e.untrusted().contentType());
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("part", e.part());
            m.put("size", e.size());
            m.put("inline", e.inline());
            m.put("untrusted", untrusted);
            entries.add(m);
        }
        return entries;
    }
}
