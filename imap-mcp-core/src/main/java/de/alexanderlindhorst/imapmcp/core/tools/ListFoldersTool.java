package de.alexanderlindhorst.imapmcp.core.tools;

import de.alexanderlindhorst.imapmcp.core.content.UntrustedText;
import de.alexanderlindhorst.imapmcp.core.folder.FolderResolver;
import de.alexanderlindhorst.imapmcp.core.folder.FolderRole;
import de.alexanderlindhorst.imapmcp.core.imap.pool.ImapLease;
import de.alexanderlindhorst.imapmcp.core.imap.pool.StoreLeases;
import de.alexanderlindhorst.imapmcp.core.tool.ToolCallContext;
import de.alexanderlindhorst.imapmcp.core.tool.ToolDefinition;
import de.alexanderlindhorst.imapmcp.core.tool.ToolHandler;
import de.alexanderlindhorst.imapmcp.core.tool.ToolHints;
import de.alexanderlindhorst.imapmcp.core.tool.ToolOutcome;
import jakarta.mail.Folder;
import jakarta.mail.MessagingException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.eclipse.angus.mail.imap.IMAPFolder;

/**
 * {@code list_folders} (SPEC 4): flat folder listing of one account with SPECIAL-USE role, attributes, sort order
 * and a cap. A single {@code LIST "" "*"} as a store-level command; no folder is opened. Roles come from the
 * LIST attributes only (SPEC 5.4), never from names. {@code folder} is the server's decoded full name, returned
 * verbatim because clients send it back; the display field {@code name} passes through
 * {@link UntrustedText#singleLine} because auto-created folder names can derive from sender-controlled headers.
 * Counts are filled by a {@link CountEnricher} ({@link StatusCountEnricher} by default); without one they are
 * null and {@code counts_complete} is false.
 */
public final class ListFoldersTool implements ToolHandler {

    public static final String NAME = "list_folders";
    public static final int DEFAULT_MAX_FOLDERS = 100;
    static final int NAME_MAX = 255;

    /**
     * Fills {@code total}/{@code unread} of the (already capped) entries; returns whether all counts of the
     * selectable folders are known. Implementations must never throw for a single failing folder.
     */
    @FunctionalInterface
    public interface CountEnricher {
        CountEnricher NONE = (lease, ctx, entries) -> false;

        boolean enrich(ImapLease lease, ToolCallContext ctx, List<Map<String, Object>> entries) throws MessagingException;
    }

    static final Map<String, Object> INPUT_SCHEMA = schema("type", "object",
            "properties", schema("account", schema("type", "string"),
                    "include_counts", schema("type", "boolean", "default", true)),
            "required", List.of("account"), "additionalProperties", false);

    private static final Map<String, Object> ITEM_SCHEMA = schema("type", "object",
            "properties", schema("folder", schema("type", "string"),
                    "name", schema("type", "string"),
                    "parent", schema("type", List.of("string", "null")),
                    "delimiter", schema("type", List.of("string", "null")),
                    "role", schema("type", List.of("string", "null"), "enum", rolesWithNull()),
                    "selectable", schema("type", "boolean"),
                    "attributes", schema("type", "array", "items", schema("type", "string")),
                    "total", schema("type", List.of("integer", "null")),
                    "unread", schema("type", List.of("integer", "null"))),
            "required", List.of("folder", "name", "parent", "delimiter", "role", "selectable", "attributes",
                    "total", "unread"));

    static final Map<String, Object> OUTPUT_SCHEMA = schema("type", "object",
            "properties", schema("account", schema("type", "string"),
                    "folders", schema("type", "array", "items", ITEM_SCHEMA),
                    "folders_truncated", schema("type", "boolean"),
                    "counts_complete", schema("type", "boolean")),
            "required", List.of("account", "folders", "folders_truncated", "counts_complete"));

    public static final ToolDefinition DEFINITION = new ToolDefinition(NAME, "List mail folders",
            "List the folders of one mail account with full name, parent, special-use role and attributes, "
                    + "sorted with INBOX first. Pass a returned folder or role to other tools. selectable=false "
                    + "means the folder cannot be searched (IMAP \\Noselect/\\NonExistent, or full name longer "
                    + "than 1000 UTF-8 bytes). Read-only; does not change flags or mark messages as read.",
            INPUT_SCHEMA, OUTPUT_SCHEMA, ToolHints.READ_ONLY_OPEN_WORLD);

    private final AccountArgument accounts;
    private final StoreLeases leases;
    private final int maxFolders;
    private final CountEnricher counts;

    public ListFoldersTool(AccountArgument accounts, StoreLeases leases) {
        this(accounts, leases, DEFAULT_MAX_FOLDERS);
    }

    public ListFoldersTool(AccountArgument accounts, StoreLeases leases, int maxFolders) {
        this(accounts, leases, maxFolders, new StatusCountEnricher());
    }

    public ListFoldersTool(AccountArgument accounts, StoreLeases leases, int maxFolders, CountEnricher counts) {
        this.accounts = Objects.requireNonNull(accounts, "accounts");
        this.leases = Objects.requireNonNull(leases, "leases");
        if (maxFolders < 1) {
            throw new IllegalArgumentException("maxFolders must be positive");
        }
        this.maxFolders = maxFolders;
        this.counts = Objects.requireNonNull(counts, "counts");
    }

    @Override
    public ToolOutcome handle(ToolCallContext ctx, Map<String, Object> args) {
        AccountArgument.Result r = accounts.resolve(ctx, (String) args.get("account"));
        if (r instanceof AccountArgument.Result.NotFound nf) {
            return new ToolOutcome.Failure(nf.error());
        }
        AccountArgument.Result.Ok ok = (AccountArgument.Result.Ok) r;
        boolean includeCounts = !Boolean.FALSE.equals(args.get("include_counts"));
        try {
            return leases.withLease(ok.key(), ctx, lease -> {
                Folder[] listed = lease.store().getDefaultFolder().list("*");
                Map<String, Object> described = describe(listed, maxFolders);
                @SuppressWarnings("unchecked")
                List<Map<String, Object>> entries = (List<Map<String, Object>>) described.get("folders");
                if (includeCounts && counts.enrich(lease, ctx, entries)) {
                    described.put("counts_complete", true);
                }
                Map<String, Object> out = new LinkedHashMap<>();
                out.put("account", ok.account().handle());
                out.putAll(described);
                return new ToolOutcome.Success(out);
            });
        } catch (MessagingException e) {
            throw new IllegalStateException("LIST failed", e); // mapped by the ToolExecutor
        }
    }

    /** Pure mapping of a LIST result: entries sorted and capped, plus the truncation and counts flags. */
    static Map<String, Object> describe(Folder[] listed, int max) {
        List<Map<String, Object>> all = new ArrayList<>();
        for (Folder f : listed) {
            all.add(entry((IMAPFolder) f));
        }
        all.sort((a, b) -> {
            boolean ia = isInbox((String) a.get("folder"));
            boolean ib = isInbox((String) b.get("folder"));
            if (ia != ib) {
                return ia ? -1 : 1;
            }
            return FolderResolver.compareCodePoints((String) a.get("folder"), (String) b.get("folder"));
        });
        boolean truncated = all.size() > max;
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("folders", new ArrayList<>(truncated ? all.subList(0, max) : all));
        out.put("folders_truncated", truncated);
        out.put("counts_complete", false);
        return out;
    }

    private static Map<String, Object> entry(IMAPFolder f) {
        String full = f.getFullName();
        Character delimiter = delimiter(f);
        String name = full;
        String parent = null;
        if (delimiter != null) {
            int idx = full.lastIndexOf(delimiter);
            if (idx >= 0) {
                name = full.substring(idx + 1);
                parent = full.substring(0, idx);
            }
        }
        String[] raw;
        try {
            raw = f.getAttributes();
        } catch (MessagingException e) {
            throw new IllegalStateException("LIST attributes unavailable", e);
        }
        List<String> attrs = raw == null ? List.of() : List.of(raw);
        boolean selectable = attrs.stream().noneMatch(a -> "\\Noselect".equalsIgnoreCase(a)
                || "\\NonExistent".equalsIgnoreCase(a))
                && full.getBytes(StandardCharsets.UTF_8).length <= FolderResolver.MAX_FOLDER_NAME_BYTES;
        String role = isInbox(full) ? FolderRole.INBOX.wire() : attrs.stream()
                .map(FolderRole::fromAttribute).flatMap(java.util.Optional::stream).findFirst()
                .map(FolderRole::wire).orElse(null);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("folder", full);
        m.put("name", UntrustedText.singleLine(name, NAME_MAX));
        m.put("parent", parent);
        m.put("delimiter", delimiter == null ? null : String.valueOf(delimiter));
        m.put("role", role);
        m.put("selectable", selectable);
        m.put("attributes", attrs);
        m.put("total", null);
        m.put("unread", null);
        return m;
    }

    /** NIL delimiter is reported by Angus as an unknown separator (U+FFFF or NUL). */
    private static Character delimiter(IMAPFolder f) {
        try {
            char c = f.getSeparator();
            return c == '\uffff' || c == '\0' ? null : c;
        } catch (MessagingException e) {
            throw new IllegalStateException("LIST delimiter unavailable", e);
        }
    }

    private static boolean isInbox(String full) {
        return "INBOX".equalsIgnoreCase(full);
    }

    private static List<Object> rolesWithNull() {
        List<Object> l = new ArrayList<>();
        for (FolderRole r : FolderRole.values()) {
            l.add(r.wire());
        }
        l.add(null);
        return l;
    }

    private static Map<String, Object> schema(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            m.put((String) kv[i], kv[i + 1]);
        }
        return m;
    }
}
