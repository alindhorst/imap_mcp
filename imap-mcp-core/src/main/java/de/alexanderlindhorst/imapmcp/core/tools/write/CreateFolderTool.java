package de.alexanderlindhorst.imapmcp.core.tools.write;

import de.alexanderlindhorst.imapmcp.core.content.UntrustedText;
import de.alexanderlindhorst.imapmcp.core.error.ToolError;
import de.alexanderlindhorst.imapmcp.core.folder.FolderResolver;
import de.alexanderlindhorst.imapmcp.core.tool.DescriptionRules;
import de.alexanderlindhorst.imapmcp.core.tool.InputValidator;
import de.alexanderlindhorst.imapmcp.core.tool.ToolDefinition;
import de.alexanderlindhorst.imapmcp.core.tool.ToolHints;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * {@code create_folder} definition and argument parsing (SPEC 4, v2 write contract, check order step 1). The
 * server-independent {@code name} checks live here; the delimiter-dependent ones (step 5) need the server and
 * belong to the execution. {@code parent} is a verbatim full name and is not sanitized.
 */
public final class CreateFolderTool {

    public static final String NAME = "create_folder";
    static final int NAME_MAX = 255;
    static final int PARENT_MAX = FolderResolver.MAX_FOLDER_NAME_BYTES;

    /** No C0, DEL, C1, {@code %} or {@code *}; \A and \z because {@code $} would accept a trailing newline. */
    private static final String NAME_PATTERN = "\\A[^\\u0000-\\u001F\\u007F-\\u009F%*]+\\z";

    static final Map<String, Object> INPUT_SCHEMA = schema("type", "object",
            "properties", schema(
                    "account", schema("type", "string"),
                    "parent", schema("type", "string", "minLength", 1, "maxLength", PARENT_MAX),
                    "name", schema("type", "string", "minLength", 1, "maxLength", NAME_MAX,
                            "pattern", NAME_PATTERN)),
            "required", List.of("account", "name"), "additionalProperties", false);

    static final Map<String, Object> OUTPUT_SCHEMA = schema("type", "object",
            "properties", schema(
                    "account", schema("type", "string"),
                    "folder", schema("type", "string"),
                    "parent", schema("type", List.of("string", "null")),
                    "delimiter", schema("type", List.of("string", "null")),
                    "created", schema("type", "boolean"),
                    "subscribed", schema("type", "boolean")),
            "required", List.of("account", "folder", "parent", "delimiter", "created", "subscribed"));

    public static final ToolDefinition DEFINITION = new ToolDefinition(NAME, "Create a mail folder",
            "Create a folder, optionally below a parent folder, and subscribe to it. A folder that already "
                    + "exists is success with created=false. This tool does not delete folders; there is no "
                    + "way to undo it within this server. " + DescriptionRules.WRITE_SENTENCE + ".",
            INPUT_SCHEMA, OUTPUT_SCHEMA, ToolHints.WRITE_ADDITIVE_IDEMPOTENT);

    private static final InputValidator VALIDATOR = new InputValidator(INPUT_SCHEMA);

    /** Either valid arguments ({@code error} null) or the INVALID_ARGUMENT result. */
    public record Parsed(String account, String parent, String name, ToolError error) {
    }

    private CreateFolderTool() {
    }

    /** Schema check, then the server-independent code checks (step 1); no I/O. */
    public static Parsed parse(Map<String, Object> args, String requestId) {
        InputValidator.Result r = VALIDATOR.validate(args, requestId);
        if (!r.valid()) {
            return failed(r.error());
        }
        String name = (String) r.args().get("name");
        String parent = (String) r.args().get("parent");
        if (parent != null && parent.getBytes(StandardCharsets.UTF_8).length > PARENT_MAX) {
            return failed(ToolError.invalidArgument("parent", "out_of_range", PARENT_MAX, requestId));
        }
        if (!name.equals(name.strip()) || ".".equals(name) || "..".equals(name)
                || !name.equals(UntrustedText.singleLine(name, Integer.MAX_VALUE))
                || Character.isWhitespace(name.codePointAt(0))
                || Character.isWhitespace(name.codePointBefore(name.length()))) {
            return failed(ToolError.invalidArgument("name", "format", null, requestId));
        }
        return new Parsed((String) r.args().get("account"), parent, name, null);
    }

    private static Parsed failed(ToolError e) {
        return new Parsed(null, null, null, e);
    }

    private static Map<String, Object> schema(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            m.put((String) kv[i], kv[i + 1]);
        }
        return m;
    }
}
