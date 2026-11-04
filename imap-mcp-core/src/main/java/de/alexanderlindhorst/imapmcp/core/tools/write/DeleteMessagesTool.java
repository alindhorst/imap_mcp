package de.alexanderlindhorst.imapmcp.core.tools.write;

import de.alexanderlindhorst.imapmcp.core.account.PoolKey;
import de.alexanderlindhorst.imapmcp.core.error.ImapErrorMapper;
import de.alexanderlindhorst.imapmcp.core.error.ToolError;
import de.alexanderlindhorst.imapmcp.core.folder.FolderResolver;
import de.alexanderlindhorst.imapmcp.core.handle.MessageHandles;
import de.alexanderlindhorst.imapmcp.core.imap.pool.ImapLease;
import de.alexanderlindhorst.imapmcp.core.imap.pool.StoreLeases;
import de.alexanderlindhorst.imapmcp.core.imap.pool.WriteCapabilities;
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
 * {@code delete_messages} (SPEC 4 v2 write contract, 5.4): a move of up to 100 handles to the {@code \Trash} role
 * folder, resolved by SPECIAL-USE only (none: {@code FOLDER_NOT_FOUND} with a hint to use {@code move_messages}).
 * Same flow as {@link MoveMessagesTool} with the same {@link MoveStep}: groups whose folder is Trash are
 * {@code already_in_target} without a command. Never a permanent delete: a refused move (full Trash) fails the
 * group and there is no fallback to deleting. Handles, folder names and server text are never logged.
 */
public final class DeleteMessagesTool implements ToolHandler {

    public static final String NAME = "delete_messages";

    /** Resolves the Trash folder; tests substitute a fixed answer because GreenMail reports no SPECIAL-USE. */
    @FunctionalInterface
    interface TrashResolver {
        FolderResolver.Resolution resolve(ImapLease lease, String requestId) throws MessagingException;
    }

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
        return schema("type", "object", "properties", p, "required", List.of("account", "handles"),
                "additionalProperties", false);
    }

    public static final ToolDefinition DEFINITION = new ToolDefinition(NAME, "Delete messages",
            "Move up to 100 messages by handle into the Trash folder and return their Trash handles; not a permanent "
                    + "delete. Undo with move_messages and the Trash handles. On Gmail, delete removes all labels. "
                    + DescriptionRules.WRITE_SENTENCE + ".",
            inputSchema(), BulkOutputSchema.of(BulkResult.Kind.DELETE), ToolHints.WRITE_DESTRUCTIVE_IDEMPOTENT);

    private final WritePreflight preflight;
    private final StoreLeases leases;
    private final MessageHandles handles;
    private final FolderGroupRunner runner;
    private final TrashResolver trash;
    private final MoveStep.Mover mover;

    public DeleteMessagesTool(WritePreflight preflight, StoreLeases leases, MessageHandles handles, Clock clock) {
        this(preflight, leases, handles, clock, TargetResolver::resolveTrash);
    }

    /** Default wiring with a substituted Trash resolution (tests). */
    DeleteMessagesTool(WritePreflight preflight, StoreLeases leases, MessageHandles handles, Clock clock,
            TrashResolver trash) {
        this(preflight, leases, handles, clock, trash, UidCommands::uidMove);
    }

    DeleteMessagesTool(WritePreflight preflight, StoreLeases leases, MessageHandles handles, Clock clock,
            TrashResolver trash, MoveStep.Mover mover) {
        this.preflight = Objects.requireNonNull(preflight, "preflight");
        this.leases = Objects.requireNonNull(leases, "leases");
        this.handles = Objects.requireNonNull(handles, "handles");
        this.runner = new FolderGroupRunner(Objects.requireNonNull(clock, "clock"));
        this.trash = Objects.requireNonNull(trash, "trash");
        this.mover = Objects.requireNonNull(mover, "mover");
    }

    @Override
    @SuppressWarnings("unchecked")
    public ToolOutcome handle(ToolCallContext ctx, Map<String, Object> args) {
        long start = System.nanoTime();
        String rid = ctx.requestId();
        String account = (String) args.get("account");
        List<String> handleList = List.copyOf((List<String>) args.get("handles"));
        WritePreflight.Result checked = preflight.check(ctx, account, handleList, null);
        if (!(checked instanceof WritePreflight.Result.Ok ok)) {
            return failure(((WritePreflight.Result.Failed) checked).error(), start);
        }
        try {
            return leases.withLease(PoolKey.of(ctx.accountContext(), ok.account()), ctx, lease -> {
                try {
                    return run(lease, ctx, ok, account, handleList, start);
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
            List<String> handleList) throws MessagingException {
        return run(lease, ctx, ok, account, handleList, System.nanoTime());
    }

    private ToolOutcome run(ImapLease lease, ToolCallContext ctx, WritePreflight.Result.Ok ok, String account,
            List<String> handleList, long start) throws MessagingException {
        String rid = ctx.requestId();
        WriteCapabilities caps = lease.capabilities();
        Optional<ToolError> gate = caps.requireUidplus(rid);
        if (gate.isPresent()) {
            return failure(gate.get(), start);
        }
        FolderResolver.Resolution target = trash.resolve(lease, rid);
        if (target.error() != null) {
            return failure(target.error(), start);
        }
        String trashName = target.fullName();
        var tenant = ctx.accountContext().tenantId();
        var accountId = ok.account().account().id();
        List<BulkResult.Item> items = new ArrayList<>();
        List<WritePreflight.Group> toRun = new ArrayList<>();
        for (WritePreflight.Group g : ok.groups()) {
            if (g.folder().equals(trashName)) {
                g.items().forEach(i -> items.add(BulkResult.Item.alreadyInTarget(i.handle())));
            } else {
                toRun.add(g);
            }
        }
        MoveStep step = new MoveStep(trashName, caps.move(), ref -> handles.encode(tenant, accountId, ref), rid,
                mover);
        items.addAll(runner.run(lease, ctx, toRun, FolderGroupRunner.AbsentStatus.ALREADY_GONE, step));
        BulkResult result = BulkResult.inInputOrder(BulkResult.Kind.DELETE, account, trashName, handleList, items);
        WriteCallLog.bulk(NAME, result, ok.groups().size(), false, Duration.ofNanos(System.nanoTime() - start));
        return new ToolOutcome.Success(result.toMap());
    }

    private static ToolOutcome failure(ToolError e, long start) {
        WriteCallLog.error(NAME, e.code(), Duration.ofNanos(System.nanoTime() - start));
        return new ToolOutcome.Failure(e);
    }
}
