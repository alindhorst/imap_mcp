package de.alexanderlindhorst.imapmcp.core.tools.write;

import de.alexanderlindhorst.imapmcp.core.account.PoolKey;
import de.alexanderlindhorst.imapmcp.core.error.ImapErrorMapper;
import de.alexanderlindhorst.imapmcp.core.error.ToolError;
import de.alexanderlindhorst.imapmcp.core.folder.FolderResolver;
import de.alexanderlindhorst.imapmcp.core.folder.FolderRole;
import de.alexanderlindhorst.imapmcp.core.handle.MessageHandles;
import de.alexanderlindhorst.imapmcp.core.imap.pool.ImapLease;
import de.alexanderlindhorst.imapmcp.core.imap.pool.StoreLeases;
import de.alexanderlindhorst.imapmcp.core.imap.pool.WriteCapabilities;
import de.alexanderlindhorst.imapmcp.core.search.SearchSchema;
import de.alexanderlindhorst.imapmcp.core.tool.DescriptionRules;
import de.alexanderlindhorst.imapmcp.core.tool.ToolCallContext;
import de.alexanderlindhorst.imapmcp.core.tool.ToolDefinition;
import de.alexanderlindhorst.imapmcp.core.tool.ToolHandler;
import de.alexanderlindhorst.imapmcp.core.tool.ToolHints;
import de.alexanderlindhorst.imapmcp.core.tool.ToolOutcome;
import jakarta.mail.MessagingException;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * {@code move_messages} (SPEC 4 v2 write contract, 5.4): moves up to 100 handles to one target folder with
 * {@code UID MOVE} (or, without MOVE, COPY + STORE \\Deleted + UID EXPUNGE of the copied UIDs) and returns the new handles from COPYUID. Check order: schema and target shape, account,
 * read-only account, handles, duplicates and folder cap ({@link WritePreflight}), lease, UIDPLUS gate before any
 * SELECT, target resolution by LIST (never selected), then the groups by {@link FolderGroupRunner} with
 * {@link MoveStep}. Groups whose folder is the target are {@code already_in_target} without a command. One leased
 * Store per call. Handles, folder names and server text are never logged.
 */
public final class MoveMessagesTool implements ToolHandler {

    public static final String NAME = "move_messages";

    private static Map<String, Object> schema(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            m.put((String) kv[i], kv[i + 1]);
        }
        return m;
    }

    private static Map<String, Object> inputSchema() {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("account", schema("type", "string", "minLength", 1, "maxLength", 132));
        p.put("handles", schema("type", "array", "minItems", 1, "maxItems", SetFlagsTool.MAX_HANDLES,
                "uniqueItems", true, "items", schema("type", "string", "minLength", 1)));
        p.put("target_folder", schema("type", "string", "minLength", 1, "maxLength", 1000, "description",
                "Full name of the target folder, verbatim. Give this or target_folder_role."));
        p.put("target_folder_role", schema("type", "string", "enum", SearchSchema.FOLDER_ROLES, "description",
                "Role of the target folder instead of its name."));
        return schema("type", "object", "properties", p, "required", List.of("account", "handles"),
                "additionalProperties", false);
    }

    public static final ToolDefinition DEFINITION = new ToolDefinition(NAME, "Move messages",
            "Move up to 100 messages by handle into one target folder and return their new handles. Undo by moving "
                    + "back with the new handles. On Gmail, a move replaces the source label; moving into \\All "
                    + "archives, moving out of Trash untrashes. " + DescriptionRules.WRITE_SENTENCE + ".",
            inputSchema(), BulkOutputSchema.of(BulkResult.Kind.MOVE), ToolHints.WRITE_DESTRUCTIVE_IDEMPOTENT);

    private final WritePreflight preflight;
    private final StoreLeases leases;
    private final MessageHandles handles;
    private final FolderGroupRunner runner;
    private final Clock clock;

    public MoveMessagesTool(WritePreflight preflight, StoreLeases leases, MessageHandles handles, Clock clock) {
        this(preflight, leases, handles, clock, new FolderGroupRunner(clock));
    }

    /** Test seam: a runner with a fault-injection hook. */
    MoveMessagesTool(WritePreflight preflight, StoreLeases leases, MessageHandles handles, Clock clock,
            FolderGroupRunner runner) {
        this.preflight = Objects.requireNonNull(preflight, "preflight");
        this.leases = Objects.requireNonNull(leases, "leases");
        this.handles = Objects.requireNonNull(handles, "handles");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.runner = Objects.requireNonNull(runner, "runner");
    }

    @Override
    @SuppressWarnings("unchecked")
    public ToolOutcome handle(ToolCallContext ctx, Map<String, Object> args) {
        long start = System.nanoTime();
        String rid = ctx.requestId();
        String account = (String) args.get("account");
        List<String> handleList = List.copyOf((List<String>) args.get("handles"));
        String targetFolder = (String) args.get("target_folder");
        FolderRole role = args.get("target_folder_role") instanceof String s
                ? FolderRole.fromWire(s).orElseThrow() : null;
        ToolError shape = TargetResolver.checkShape(targetFolder, role, rid);
        if (shape != null) {
            return failure(shape, start);
        }
        WritePreflight.Result checked = preflight.check(ctx, account, handleList, null);
        if (!(checked instanceof WritePreflight.Result.Ok ok)) {
            return failure(((WritePreflight.Result.Failed) checked).error(), start);
        }
        try {
            return leases.withLease(PoolKey.of(ctx.accountContext(), ok.account()), ctx, lease -> {
                try {
                    return run(lease, ctx, ok, account, handleList, targetFolder, role, start);
                } catch (MessagingException e) {
                    ImapErrorMapper.MappedError mapped = ImapErrorMapper.map(e, rid);
                    if (mapped.discardConnection()) {
                        lease.markBroken();
                    }
                    return failure(mapped.error(), start);
                }
            });
        } catch (MessagingException | RuntimeException e) {
            return failure(ImapErrorMapper.map(e, rid).error(), start);
        }
    }

    /** Steps 7 to 9 on a leased connection; package-private so tests can force the connection's capabilities. */
    ToolOutcome onLease(ImapLease lease, ToolCallContext ctx, WritePreflight.Result.Ok ok, String account,
            List<String> handleList, String targetFolder, FolderRole role) throws MessagingException {
        return run(lease, ctx, ok, account, handleList, targetFolder, role, System.nanoTime());
    }

    private ToolOutcome run(ImapLease lease, ToolCallContext ctx, WritePreflight.Result.Ok ok, String account,
            List<String> handleList, String targetFolder, FolderRole role, long start) throws MessagingException {
        String rid = ctx.requestId();
        WriteCapabilities caps = lease.capabilities();
        Optional<ToolError> gate = caps.requireUidplus(rid);
        if (gate.isPresent()) {
            return failure(gate.get(), start);
        }
        FolderResolver.Resolution target = TargetResolver.resolve(lease, targetFolder, role, rid);
        if (target.error() != null) {
            return failure(target.error(), start);
        }
        String targetName = target.fullName();
        var tenant = ctx.accountContext().tenantId();
        var accountId = ok.account().account().id();
        List<BulkResult.Item> items = new ArrayList<>();
        List<WritePreflight.Group> toRun = new ArrayList<>();
        for (WritePreflight.Group g : ok.groups()) {
            if (g.folder().equals(targetName)) {
                g.items().forEach(i -> items.add(BulkResult.Item.alreadyInTarget(i.handle())));
            } else {
                toRun.add(g);
            }
        }
        MoveStep step = new MoveStep(targetName, caps.move(), ref -> handles.encode(tenant, accountId, ref), rid);
        items.addAll(runner.run(lease, ctx, toRun, FolderGroupRunner.AbsentStatus.ALREADY_GONE, step));
        BulkResult result = BulkResult.inInputOrder(BulkResult.Kind.MOVE, account, targetName, handleList, items);
        WriteCallLog.bulk(NAME, result, ok.groups().size(), step.usedFallback(), Duration.ofNanos(System.nanoTime() - start));
        return new ToolOutcome.Success(result.toMap());
    }

    private static ToolOutcome failure(ToolError e, long start) {
        WriteCallLog.error(NAME, e.code(), Duration.ofNanos(System.nanoTime() - start));
        return new ToolOutcome.Failure(e);
    }
}
