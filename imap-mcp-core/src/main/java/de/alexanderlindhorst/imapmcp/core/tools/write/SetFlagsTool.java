package de.alexanderlindhorst.imapmcp.core.tools.write;

import de.alexanderlindhorst.imapmcp.core.error.ToolError;
import de.alexanderlindhorst.imapmcp.core.tool.DescriptionRules;
import de.alexanderlindhorst.imapmcp.core.tool.ImapAtoms;
import de.alexanderlindhorst.imapmcp.core.tool.ToolDefinition;
import de.alexanderlindhorst.imapmcp.core.tool.ToolHints;
import de.alexanderlindhorst.imapmcp.core.imap.pool.StoreLeases;
import de.alexanderlindhorst.imapmcp.core.tool.ToolCallContext;
import de.alexanderlindhorst.imapmcp.core.tool.ToolHandler;
import de.alexanderlindhorst.imapmcp.core.tool.ToolOutcome;
import de.alexanderlindhorst.imapmcp.core.tools.AccountArgument;
import de.alexanderlindhorst.imapmcp.core.tools.MessageLocator;
import jakarta.mail.Flags;
import java.time.Clock;
import java.util.function.Function;
import org.eclipse.angus.mail.imap.IMAPFolder;
import de.alexanderlindhorst.imapmcp.core.account.PoolKey;
import de.alexanderlindhorst.imapmcp.core.error.ImapErrorMapper;
import jakarta.mail.MessagingException;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * {@code set_flags} (SPEC 4, v2 write contract): definition, argument parsing and execution.
 * {@link #parse} is the second validation step after {@code InputValidator} (code checks {@code reserved},
 * {@code duplicate}, {@code conflict}, {@code no_change}) and yields at most one add set and one remove set with
 * system flags and keywords merged. {@code \Deleted} can only be cleared, {@code \Recent} is never settable.
 */
public final class SetFlagsTool implements ToolHandler {

    public static final String NAME = "set_flags";

    public static final int MAX_HANDLES = 100;
    public static final int MAX_KEYWORDS = 10;

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
                        ImapAtoms.KEYWORD_PATTERN));
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

    public static final ToolDefinition DEFINITION = new ToolDefinition(NAME, "Set message flags",
            "Add or remove flags (seen, flagged, answered, draft) and keywords on up to 100 messages by handle; "
                    + "deleted can only be cleared. Undo by calling set_flags again with the opposite value. "
                    + "On Gmail, flags apply to the message across labels. "
                    + DescriptionRules.WRITE_SENTENCE + ".",
            inputSchema(), BulkOutputSchema.of(BulkResult.Kind.SET_FLAGS), ToolHints.WRITE_DESTRUCTIVE_IDEMPOTENT);

    /**
     * Valid: {@code add} and {@code remove} are the flag/keyword atoms to store (system flags as {@code \Seen}
     * etc., keywords as given), each possibly empty but not both. Invalid: {@code error} is INVALID_ARGUMENT.
     */
    public record Parsed(List<String> handles, List<String> add, List<String> remove, ToolError error) {
        public boolean valid() {
            return error == null;
        }
    }

    private final WritePreflight preflight;
    private final StoreLeases leases;
    private final Clock clock;
    private final FolderGroupRunner runner;
    private final Function<IMAPFolder, Flags> permanentFlags;

    public SetFlagsTool(AccountArgument accounts, MessageLocator locator, StoreLeases leases, Clock clock) {
        this(accounts, locator, leases, clock, IMAPFolder::getPermanentFlags);
    }

    /** {@code permanentFlags} is the test seam for a server whose PERMANENTFLAGS lacks {@code \*}. */
    SetFlagsTool(AccountArgument accounts, MessageLocator locator, StoreLeases leases, Clock clock,
            Function<IMAPFolder, Flags> permanentFlags) {
        this.preflight = new WritePreflight(accounts, locator);
        this.leases = Objects.requireNonNull(leases, "leases");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.runner = new FolderGroupRunner(clock);
        this.permanentFlags = Objects.requireNonNull(permanentFlags, "permanentFlags");
    }

    @Override
    public ToolOutcome handle(ToolCallContext ctx, Map<String, Object> args) {
        Instant start = clock.instant();
        String account = (String) args.get("account");
        Parsed parsed = parse(args, ctx.requestId());
        if (!parsed.valid()) {
            return fail(parsed.error(), start);
        }
        WritePreflight.Result.Ok checked;
        switch (preflight.check(ctx, account, parsed.handles(), null)) {
            case WritePreflight.Result.Failed f -> {
                return fail(f.error(), start);
            }
            case WritePreflight.Result.Ok ok -> checked = ok;
        }
        Flags add = flags(parsed.add());
        Flags remove = flags(parsed.remove());
        boolean needsKeywords = add.getUserFlags().length > 0;
        String rid = ctx.requestId();
        try {
            List<BulkResult.Item> items = leases.withLease(PoolKey.of(ctx.accountContext(), checked.account()), ctx,
                    lease -> runner.run(lease, ctx, checked.groups(), FolderGroupRunner.AbsentStatus.NOT_FOUND,
                            (folder, present, gate) -> store(folder, present, gate, add, remove, needsKeywords, rid)));
            BulkResult result = BulkResult.inInputOrder(BulkResult.Kind.SET_FLAGS, account, null, parsed.handles(),
                    items);
            WriteCallLog.bulk(NAME, result, checked.groups().size(), false, Duration.between(start, clock.instant()));
            return new ToolOutcome.Success(result.toMap());
        } catch (MessagingException | RuntimeException e) {
            if (ctx.mutationStarted() && e instanceof RuntimeException re) {
                throw re; // the executor reports the unknown outcome (SPEC 5.5)
            }
            return fail(ImapErrorMapper.map(e, rid).error(), start);
        }
    }

    private static ToolOutcome fail(ToolError error, Instant start) {
        WriteCallLog.error(NAME, error.code(), Duration.between(start, Instant.now()));
        return new ToolOutcome.Failure(error);
    }

    /** At most one add and one remove STORE (+FLAGS.SILENT / -FLAGS.SILENT) for the present UIDs of a group. */
    private List<BulkResult.Item> store(IMAPFolder folder, List<WritePreflight.Item> present,
            FolderGroupRunner.Gate gate, Flags add, Flags remove, boolean needsKeywords, String rid)
            throws MessagingException {
        if (needsKeywords && !keywordsAllowed(folder, add)) {
            return failAll(present, ToolError.unsupported("keywords", rid));
        }
        List<Long> uids = present.stream().map(i -> i.ref().uid()).toList();
        for (var step : List.of(Map.entry(UidCommands.FlagMode.ADD, add), Map.entry(UidCommands.FlagMode.REMOVE, remove))) {
            if (isEmpty(step.getValue())) {
                continue;
            }
            gate.beforeWrite();
            UidCommands.Result r = UidCommands.uidStore(folder, uids, step.getKey(), step.getValue());
            Optional<ToolError> refused = WriteResponses.map(r.tagged(), null, rid);
            if (refused.isPresent()) {
                return failAll(present, refused.get());
            }
        }
        return present.stream().map(i -> BulkResult.Item.okFlags(i.handle())).toList();
    }

    private boolean keywordsAllowed(IMAPFolder folder, Flags add) {
        Flags permanent = permanentFlags.apply(folder);
        if (permanent == null) {
            return false;
        }
        if (permanent.contains(Flags.Flag.USER)) { // PERMANENTFLAGS \*
            return true;
        }
        Set<String> listed = new java.util.HashSet<>();
        for (String k : permanent.getUserFlags()) {
            listed.add(k.toLowerCase(Locale.ROOT));
        }
        for (String k : add.getUserFlags()) {
            if (!listed.contains(k.toLowerCase(Locale.ROOT))) {
                return false;
            }
        }
        return true;
    }

    private static List<BulkResult.Item> failAll(List<WritePreflight.Item> present, ToolError error) {
        return present.stream().map(i -> BulkResult.Item.failed(i.handle(), error)).toList();
    }

    private static boolean isEmpty(Flags f) {
        return f.getSystemFlags().length == 0 && f.getUserFlags().length == 0;
    }

    private static Flags flags(List<String> atoms) {
        Flags f = new Flags();
        for (String a : atoms) {
            switch (a) {
                case "\\Seen" -> f.add(Flags.Flag.SEEN);
                case "\\Flagged" -> f.add(Flags.Flag.FLAGGED);
                case "\\Answered" -> f.add(Flags.Flag.ANSWERED);
                case "\\Draft" -> f.add(Flags.Flag.DRAFT);
                case "\\Deleted" -> f.add(Flags.Flag.DELETED);
                default -> f.add(a); // keyword
            }
        }
        return f;
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
