package de.alexanderlindhorst.imapmcp.core.tools.write;

import de.alexanderlindhorst.imapmcp.core.content.UntrustedText;
import de.alexanderlindhorst.imapmcp.core.error.ErrorCode;
import de.alexanderlindhorst.imapmcp.core.error.ImapErrorMapper;
import de.alexanderlindhorst.imapmcp.core.error.ToolError;
import de.alexanderlindhorst.imapmcp.core.folder.FolderResolver;
import de.alexanderlindhorst.imapmcp.core.tool.DescriptionRules;
import de.alexanderlindhorst.imapmcp.core.imap.pool.ImapLease;
import de.alexanderlindhorst.imapmcp.core.imap.pool.StoreLeases;
import de.alexanderlindhorst.imapmcp.core.tool.InputValidator;
import de.alexanderlindhorst.imapmcp.core.tool.ToolCallContext;
import de.alexanderlindhorst.imapmcp.core.tool.ToolDefinition;
import de.alexanderlindhorst.imapmcp.core.tool.ToolHandler;
import de.alexanderlindhorst.imapmcp.core.tool.ToolHints;
import de.alexanderlindhorst.imapmcp.core.tool.ToolOutcome;
import de.alexanderlindhorst.imapmcp.core.tools.AccountArgument;
import jakarta.mail.Folder;
import jakarta.mail.MessagingException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import org.eclipse.angus.mail.imap.IMAPFolder;
import org.eclipse.angus.mail.imap.IMAPStore;

/**
 * {@code create_folder} definition and argument parsing (SPEC 4, v2 write contract, check order step 1). The
 * server-independent {@code name} checks live here; the delimiter-dependent ones (step 5) need the server and
 * belong to the execution. {@code parent} is a verbatim full name and is not sanitized.
 */
public final class CreateFolderTool implements ToolHandler {

    public static final String NAME = "create_folder";
    static final int NAME_MAX = 255;
    static final int PARENT_MAX = FolderResolver.MAX_FOLDER_NAME_BYTES;

    /** No C0, DEL, C1, {@code %} (x25) or {@code *} (x2a); ECMA-262 {@code ^...$}, matched against the whole value. */
    private static final String NAME_PATTERN = "^[^\\u0000-\\u001F\\u007F-\\u009F\\x25\\x2a]+$";

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

    /** Store-level LIST / NAMESPACE access of the handler (no folder is opened); a seam for tests. */
    interface Directory {

        /** Delimiter null: the server has no hierarchy. */
        record Namespace(String prefix, Character delimiter) {
        }

        record Entry(String fullName, Character delimiter, List<String> attributes) {
        }

        Namespace personalNamespace(ImapLease lease) throws MessagingException;

        /** The entry whose full name equals {@code fullName} (INBOX case-insensitively), if listed. */
        Optional<Entry> list(ImapLease lease, String fullName) throws MessagingException;
    }

    /** The write commands; a seam for tests, production is {@link UidCommands}. */
    interface Commands {
        UidCommands.Result create(ImapLease lease, String name) throws MessagingException;

        UidCommands.Result subscribe(ImapLease lease, String name) throws MessagingException;
    }

    private static final String INBOX = "INBOX";

    private final AccountArgument accounts;
    private final StoreLeases leases;
    private final Directory directory;
    private final Commands commands;

    public CreateFolderTool(AccountArgument accounts, StoreLeases leases) {
        this(accounts, leases, angusDirectory(), UIDCOMMANDS);
    }

    CreateFolderTool(AccountArgument accounts, StoreLeases leases, Directory directory, Commands commands) {
        this.accounts = Objects.requireNonNull(accounts, "accounts");
        this.leases = Objects.requireNonNull(leases, "leases");
        this.directory = Objects.requireNonNull(directory, "directory");
        this.commands = Objects.requireNonNull(commands, "commands");
    }

    private static final Commands UIDCOMMANDS = new Commands() {
        @Override
        public UidCommands.Result create(ImapLease lease, String name) throws MessagingException {
            return UidCommands.create(lease, name);
        }

        @Override
        public UidCommands.Result subscribe(ImapLease lease, String name) throws MessagingException {
            return UidCommands.subscribe(lease, name);
        }
    };

    /** LIST and NAMESPACE through Angus as store-level commands (no folder is opened, one socket). */
    static Directory angusDirectory() {
        return new Directory() {
            @Override
            public Namespace personalNamespace(ImapLease lease) throws MessagingException {
                IMAPStore store = lease.store();
                if (store.hasCapability("NAMESPACE")) {
                    Folder[] ns = store.getPersonalNamespaces();
                    if (ns != null && ns.length > 0) {
                        IMAPFolder f = (IMAPFolder) ns[0];
                        Character d = delimiter(f);
                        String name = f.getFullName(); // Angus strips the trailing delimiter of the prefix
                        return new Namespace(name.isEmpty() || d == null ? name : name + d, d);
                    }
                }
                return new Namespace("", delimiter((IMAPFolder) store.getDefaultFolder()));
            }

            @Override
            public Optional<Entry> list(ImapLease lease, String fullName) throws MessagingException {
                Folder[] found = lease.store().getDefaultFolder().list(fullName);
                for (Folder f : found == null ? new Folder[0] : found) {
                    String full = f.getFullName();
                    if (full.equals(fullName) || INBOX.equalsIgnoreCase(full) && INBOX.equalsIgnoreCase(fullName)) {
                        String[] attrs = ((IMAPFolder) f).getAttributes();
                        return Optional.of(new Entry(full, delimiter((IMAPFolder) f),
                                attrs == null ? List.of() : List.of(attrs)));
                    }
                }
                return Optional.empty();
            }
        };
    }

    /** NIL delimiter is reported by Angus as an unknown separator (U+FFFF or NUL). */
    private static Character delimiter(IMAPFolder f) throws MessagingException {
        char c = f.getSeparator();
        return c == '\uffff' || c == '\0' ? null : c;
    }

    private static boolean selectable(Directory.Entry e) {
        return e.attributes().stream().noneMatch(a -> "\\Noselect".equalsIgnoreCase(a)
                || "\\NonExistent".equalsIgnoreCase(a));
    }

    private static boolean hasAttribute(Directory.Entry e, String attribute) {
        return e.attributes().stream().anyMatch(attribute::equalsIgnoreCase);
    }

    @Override
    public ToolOutcome handle(ToolCallContext ctx, Map<String, Object> args) {
        long start = System.nanoTime();
        String rid = ctx.requestId();
        Parsed p = parse(args, rid);
        if (p.error() != null) {
            return fail(p.error(), start);
        }
        AccountArgument.Result r = accounts.resolve(ctx, p.account());
        if (r instanceof AccountArgument.Result.NotFound nf) {
            return fail(nf.error(), start);
        }
        AccountArgument.Result.Ok ok = (AccountArgument.Result.Ok) r;
        if (ok.account().account().readOnly()) {
            return fail(ToolError.permissionDenied(ToolError.PermissionReason.ACCOUNT_READ_ONLY, rid), start);
        }
        try {
            return leases.withLease(ok.key(), ctx, lease -> {
                try {
                    return execute(ctx, lease, ok.account().handle(), p, start);
                } catch (MessagingException e) {
                    ImapErrorMapper.MappedError mapped = ImapErrorMapper.map(e, rid);
                    if (mapped.discardConnection()) {
                        lease.markBroken();
                    }
                    ToolError error = mapped.error();
                    if (ctx.mutationStarted() && (error.code() == ErrorCode.TIMEOUT
                            || error.code() == ErrorCode.UPSTREAM_UNAVAILABLE
                            || error.code() == ErrorCode.INTERNAL_ERROR)) {
                        error = error.withOutcomeUnknown();
                    }
                    return fail(error, start);
                }
            });
        } catch (MessagingException e) {
            throw new IllegalStateException("unreachable: handled inside the lease", e);
        }
    }

    /** Steps 5 to 8 of the check order (SPEC 4) on the leased connection. */
    private ToolOutcome execute(ToolCallContext ctx, ImapLease lease, String accountHandle, Parsed p, long start)
            throws MessagingException {
        String rid = ctx.requestId();
        String parentFull = null;
        String prefix;
        Character delimiter;
        if (p.parent() != null) {
            Optional<Directory.Entry> parent = directory.list(lease, p.parent());
            if (parent.isEmpty() || hasAttribute(parent.get(), "\\NonExistent")) {
                return fail(ToolError.folderNotFound(p.parent(), rid), start);
            }
            if (hasAttribute(parent.get(), "\\NoInferiors")) {
                return fail(ToolError.invalidArgument("parent", "no_inferiors", null, rid), start);
            }
            if (parent.get().delimiter() == null) {
                return fail(ToolError.invalidArgument("parent", "no_hierarchy", null, rid), start);
            }
            parentFull = parent.get().fullName();
            delimiter = parent.get().delimiter();
            prefix = parentFull + delimiter;
        } else {
            Directory.Namespace ns = directory.personalNamespace(lease);
            delimiter = ns.delimiter();
            prefix = ns.prefix();
        }
        if (delimiter != null && p.name().indexOf(delimiter) >= 0) {
            return fail(ToolError.invalidArgument("name", "contains_delimiter", null, rid), start);
        }
        String full = prefix + p.name();
        if (prefix.isEmpty() && INBOX.equalsIgnoreCase(full)) {
            full = INBOX;
        }
        if (full.getBytes(StandardCharsets.UTF_8).length > PARENT_MAX) {
            return fail(ToolError.invalidArgument("name", "out_of_range", PARENT_MAX, rid), start);
        }
        boolean created = false;
        Optional<Directory.Entry> existing = directory.list(lease, full);
        if (existing.isEmpty() || !selectable(existing.get())) {
            ctx.markMutationStarted();
            UidCommands.Result result = commands.create(lease, full);
            if (result.ok()) {
                created = true;
            } else {
                Optional<Directory.Entry> now = directory.list(lease, full); // race-safe after any NO
                if (now.isEmpty() || !selectable(now.get())) {
                    return fail(WriteResponses.map(result.tagged(), full, rid)
                            .orElseGet(() -> ToolError.internalError(rid)), start);
                }
            }
        }
        ctx.markMutationStarted(); // idempotent; for created=false this is the only mutating command
        boolean subscribed;
        try {
            subscribed = commands.subscribe(lease, full).ok();
        } catch (MessagingException e) {
            lease.markBroken();
            subscribed = false;
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("account", accountHandle);
        out.put("folder", full);
        out.put("parent", parentFull);
        out.put("delimiter", delimiter == null ? null : String.valueOf(delimiter));
        out.put("created", created);
        out.put("subscribed", subscribed);
        WriteCallLog.createFolder(NAME, created, subscribed, elapsed(start));
        return new ToolOutcome.Success(out);
    }

    private static ToolOutcome fail(ToolError error, long start) {
        WriteCallLog.error(NAME, error.code(), elapsed(start));
        return new ToolOutcome.Failure(error);
    }

    private static Duration elapsed(long startNanos) {
        return Duration.ofNanos(System.nanoTime() - startNanos);
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
