package de.alexanderlindhorst.imapmcp.core.tools.write;

import de.alexanderlindhorst.imapmcp.core.account.PoolKey;
import de.alexanderlindhorst.imapmcp.core.account.ResolvedAccount;
import de.alexanderlindhorst.imapmcp.core.error.ImapErrorMapper;
import de.alexanderlindhorst.imapmcp.core.error.ToolError;
import de.alexanderlindhorst.imapmcp.core.folder.FolderResolver;
import de.alexanderlindhorst.imapmcp.core.folder.FolderRole;
import de.alexanderlindhorst.imapmcp.core.handle.MessageHandles;
import de.alexanderlindhorst.imapmcp.core.handle.MessageRef;
import de.alexanderlindhorst.imapmcp.core.imap.pool.ImapLease;
import de.alexanderlindhorst.imapmcp.core.imap.pool.StoreLeases;
import de.alexanderlindhorst.imapmcp.core.imap.pool.StoreUnavailableException;
import de.alexanderlindhorst.imapmcp.core.tool.DescriptionRules;
import de.alexanderlindhorst.imapmcp.core.tool.ToolCallContext;
import de.alexanderlindhorst.imapmcp.core.tool.ToolDefinition;
import de.alexanderlindhorst.imapmcp.core.tool.ToolHandler;
import de.alexanderlindhorst.imapmcp.core.tool.ToolHints;
import de.alexanderlindhorst.imapmcp.core.tool.ToolOutcome;
import jakarta.mail.MessagingException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import org.eclipse.angus.mail.imap.IMAPFolder;

/**
 * {@code copy_messages} (SPEC 4 v2 write contract, 5.4, 5.5): UID COPY of up to 100 handles from at most 10 source
 * folders into one target, which is resolved by LIST and never selected. Check order: argument shape, then
 * {@link WritePreflight} (account, read-only, handles, duplicates, folders, verbatim {@code same_folder}), lease,
 * UIDPLUS gate on the leased connection (before any SELECT), target resolution plus {@code same_folder} for a role
 * target, and finally the per source-folder groups of {@link FolderGroupRunner}. New handles are minted from the
 * COPYUID response, paired by position in the response sets; without COPYUID the item is {@code ok} with
 * {@code new_uid_unknown} (the target is never searched). A copy is not idempotent: the executor reports a
 * deadline or unchecked failure after the mutation started as OUTCOME_UNKNOWN. Nothing is logged here except the
 * one write-call line; no handle, folder name or server text.
 */
public final class CopyMessagesTool implements ToolHandler {

    public static final String NAME = "copy_messages";

    public static final int MAX_HANDLES = 100;

    private static final Map<String, Object> OUTPUT_SCHEMA = BulkOutputSchema.of(BulkResult.Kind.COPY);

    public static final ToolDefinition DEFINITION = new ToolDefinition(NAME, "Copy messages",
            "Copy up to 100 messages by handle into one target folder (target_folder or target_folder_role) and "
                    + "return their new handles. Repeating the call duplicates the messages; on Gmail a copy only "
                    + "adds a label. Undo by calling delete_messages with the new handles (on Gmail the label "
                    + "stays until the message is moved out of the target). "
                    + DescriptionRules.WRITE_SENTENCE + ".",
            inputSchema(), OUTPUT_SCHEMA, ToolHints.WRITE_ADDITIVE);

    /** Obtains the call's one lease; production: {@link StoreLeases#withLease}, tests may force capabilities. */
    @FunctionalInterface
    interface Leaser {
        ToolOutcome withLease(PoolKey key, ToolCallContext ctx, StoreLeases.LeaseWork<ToolOutcome> work)
                throws MessagingException;
    }

    /** The UID COPY command; production: {@link UidCommands#uidCopy}, tests script the tagged answer. */
    @FunctionalInterface
    interface CopyCommand {
        UidCommands.Result copy(IMAPFolder folder, Collection<Long> uids, String target) throws MessagingException;
    }

    private final WritePreflight preflight;
    private final Leaser leaser;
    private final MessageHandles handles;
    private final FolderGroupRunner runner;
    private final CopyCommand copy;

    public CopyMessagesTool(WritePreflight preflight, StoreLeases leases, MessageHandles handles,
            FolderGroupRunner runner) {
        this(preflight, Objects.requireNonNull(leases, "leases")::withLease, handles, runner, UidCommands::uidCopy);
    }

    CopyMessagesTool(WritePreflight preflight, Leaser leaser, MessageHandles handles, FolderGroupRunner runner,
            CopyCommand copy) {
        this.preflight = Objects.requireNonNull(preflight, "preflight");
        this.leaser = Objects.requireNonNull(leaser, "leaser");
        this.handles = Objects.requireNonNull(handles, "handles");
        this.runner = Objects.requireNonNull(runner, "runner");
        this.copy = Objects.requireNonNull(copy, "copy");
    }

    /** What the lease work needs; the preflight already settled account, handles and groups. */
    private record Request(String accountHandle, ResolvedAccount account, List<String> inputHandles,
            List<WritePreflight.Group> groups, String targetFolder, FolderRole role) {
    }

    @Override
    @SuppressWarnings("unchecked")
    public ToolOutcome handle(ToolCallContext ctx, Map<String, Object> args) {
        long start = System.nanoTime();
        String rid = ctx.requestId();
        String accountHandle = (String) args.get("account");
        List<String> inputHandles = (List<String>) args.get("handles");
        String targetFolder = (String) args.get("target_folder");
        FolderRole role = args.get("target_folder_role") instanceof String s ? FolderRole.fromWire(s).orElse(null)
                : null;
        ToolError shape = TargetResolver.checkShape(targetFolder, role, rid);
        if (shape != null) {
            ctx.rejectArguments(); // step 1 (D10)
            return fail(shape, start);
        }
        WritePreflight.Result checked = preflight.check(ctx, accountHandle, inputHandles, targetFolder);
        if (!(checked instanceof WritePreflight.Result.Ok ok)) {
            return fail(((WritePreflight.Result.Failed) checked).error(), start);
        }
        Request req = new Request(accountHandle, ok.account(), List.copyOf(inputHandles), ok.groups(), targetFolder,
                role);
        PoolKey key = PoolKey.of(ctx.accountContext(), ok.account());
        try {
            ToolOutcome outcome = leaser.withLease(key, ctx, lease -> run(lease, ctx, req, start));
            return outcome;
        } catch (MessagingException | StoreUnavailableException e) {
            // before the first mutating command nothing was changed: the v1 whole-call error. An unchecked failure
            // after the mark is left to the executor (OUTCOME_UNKNOWN).
            return fail(ImapErrorMapper.map(e, rid).error(), start);
        }
    }

    private ToolOutcome run(ImapLease lease, ToolCallContext ctx, Request req, long start) throws MessagingException {
        String rid = ctx.requestId();
        try {
            Optional<ToolError> unsupported = lease.capabilities().requireUidplus(rid);
            if (unsupported.isPresent()) {
                return fail(unsupported.get(), start);
            }
            FolderResolver.Resolution target = TargetResolver.resolve(lease, req.targetFolder(), req.role(), rid);
            if (target.error() != null) {
                return fail(target.error(), start);
            }
            if (req.role() != null) {
                ToolError same = TargetResolver.sameFolderForRole(target.fullName(),
                        req.groups().stream().map(WritePreflight.Group::folder).toList(), rid);
                if (same != null) {
                    return fail(same, start);
                }
            }
            String targetName = target.fullName();
            List<BulkResult.Item> items = runner.run(lease, ctx, req.groups(),
                    FolderGroupRunner.AbsentStatus.NOT_FOUND, (folder, present, gate) -> {
                        gate.beforeWrite();
                        return copyGroup(folder, present, targetName, ctx, req.account());
                    });
            BulkResult result = BulkResult.inInputOrder(BulkResult.Kind.COPY, req.accountHandle(), targetName,
                    req.inputHandles(), items);
            WriteCallLog.bulk(NAME, result, req.groups().size(), false, since(start));
            return new ToolOutcome.Success(result.toMap());
        } catch (MessagingException e) {
            ImapErrorMapper.MappedError mapped = ImapErrorMapper.map(e, rid);
            if (mapped.discardConnection()) {
                lease.markBroken();
            }
            return fail(mapped.error(), start);
        }
    }

    /** One UID COPY for the present UIDs of a source folder; the gate was already passed by the caller. */
    private List<BulkResult.Item> copyGroup(IMAPFolder folder, List<WritePreflight.Item> present,
            String target, ToolCallContext ctx, ResolvedAccount account) throws MessagingException {
        List<Long> uids = new ArrayList<>(present.size());
        present.forEach(i -> uids.add(i.ref().uid()));
        UidCommands.Result r = copy.copy(folder, uids, target);
        List<BulkResult.Item> out = new ArrayList<>(present.size());
        if (!r.ok()) {
            ToolError error = WriteResponses.map(r.tagged(), target, ctx.requestId())
                    .orElseGet(() -> ToolError.upstreamError(r.tagged().getRest(), ctx.requestId()));
            present.forEach(i -> out.add(BulkResult.Item.failed(i.handle(), error)));
            return out;
        }
        Optional<CopyUidParser.CopyUid> copyUid = r.copyUid();
        for (WritePreflight.Item i : present) {
            if (copyUid.isEmpty()) {
                out.add(BulkResult.Item.okNewUidUnknown(i.handle()));
                continue;
            }
            java.util.OptionalLong dest = copyUid.get().destinationOf(i.ref().uid());
            if (dest.isEmpty()) {
                // gone between the pre-check and the COPY: the server did not copy it
                out.add(BulkResult.Item.notFound(i.handle()));
                continue;
            }
            String newHandle = handles.encode(ctx.accountContext().tenantId(), account.account().id(),
                    new MessageRef(target, copyUid.get().uidValidity(), dest.getAsLong()));
            out.add(BulkResult.Item.ok(i.handle(), newHandle));
        }
        return out;
    }

    private static ToolOutcome fail(ToolError error, long startNanos) {
        WriteCallLog.error(NAME, error.code(), since(startNanos));
        return new ToolOutcome.Failure(error);
    }

    private static Duration since(long startNanos) {
        return Duration.ofNanos(System.nanoTime() - startNanos);
    }

    private static Map<String, Object> inputSchema() {
        Map<String, Object> roles = schema("type", "string", "description",
                "Special-use role of the target folder; use instead of target_folder.");
        List<Object> wires = new ArrayList<>();
        for (FolderRole r : FolderRole.values()) {
            wires.add(r.wire());
        }
        roles.put("enum", wires);
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("account", schema("type", "string", "minLength", 1, "maxLength", 132, "description",
                "Account handle from list_accounts."));
        p.put("handles", schema("type", "array", "minItems", 1, "maxItems", MAX_HANDLES, "uniqueItems", true,
                "description", "Message handles from search_messages (at most 10 distinct source folders).",
                "items", schema("type", "string", "minLength", 1)));
        p.put("target_folder", schema("type", "string", "minLength", 1, "maxLength", 1000, "description",
                "Full name of the target folder, verbatim as returned by list_folders. Exactly one of "
                        + "target_folder and target_folder_role is required."));
        p.put("target_folder_role", roles);
        return schema("type", "object", "properties", p, "required", List.of("account", "handles"),
                "additionalProperties", false);
    }

    private static Map<String, Object> schema(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            m.put((String) kv[i], kv[i + 1]);
        }
        return m;
    }
}
