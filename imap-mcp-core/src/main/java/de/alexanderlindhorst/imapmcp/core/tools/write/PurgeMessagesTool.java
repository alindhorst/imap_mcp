package de.alexanderlindhorst.imapmcp.core.tools.write;

import de.alexanderlindhorst.imapmcp.core.account.PoolKey;
import de.alexanderlindhorst.imapmcp.core.audit.ItemRef;
import de.alexanderlindhorst.imapmcp.core.error.ErrorCode;
import de.alexanderlindhorst.imapmcp.core.error.ImapErrorMapper;
import de.alexanderlindhorst.imapmcp.core.error.ToolError;
import de.alexanderlindhorst.imapmcp.core.folder.FolderResolver;
import de.alexanderlindhorst.imapmcp.core.folder.FolderRole;
import de.alexanderlindhorst.imapmcp.core.imap.pool.ImapLease;
import de.alexanderlindhorst.imapmcp.core.imap.pool.StoreLeases;
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
 * {@code purge_messages} (SPEC 4 v3a, 5.4): permanent removal of up to 100 handles from the {@code \Trash} role
 * folder and from no other, by {@code UID STORE +FLAGS.SILENT (\Deleted)}, {@code UID EXPUNGE} of exactly those UIDs
 * and a post-check ({@link PurgeStep}). Check order: schema, account, read-only, {@code allowPurge}, ability to
 * confirm (all without I/O), handles, duplicates, lease, UIDPLUS, scope (Trash by SPECIAL-USE only), confirmation
 * ({@link PurgeConfirmation}), then the group, whose first mutating command goes through
 * {@code tryBeginMutation()}. Consent never comes from an argument. Handles, folder names and server text are never
 * logged; the one audit line is {@link PurgeAudit}.
 */
public final class PurgeMessagesTool implements ToolHandler {

    public static final String NAME = "purge_messages";

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

    public static final ToolDefinition DEFINITION = new ToolDefinition(NAME, "Purge messages",
            "Delete messages permanently from the Trash folder. This cannot be undone. "
                    + DescriptionRules.WRITE_SENTENCE + ". Messages in other folders must first be moved to Trash "
                    + "with delete_messages. The user is asked to confirm each call unless they allowed it for this "
                    + "session. On Gmail this removes the message from all labels.",
            inputSchema(), BulkOutputSchema.of(BulkResult.Kind.PURGE), ToolHints.WRITE_DESTRUCTIVE_IDEMPOTENT);

    private final WritePreflight preflight;
    private final StoreLeases leases;
    private final FolderGroupRunner runner;
    private final PurgeConfirmation confirmation;
    private final DeleteMessagesTool.TrashResolver trash;
    private final PurgeStep.Wire wire;

    public PurgeMessagesTool(WritePreflight preflight, StoreLeases leases, Clock clock,
            PurgeConfirmation confirmation) {
        this(preflight, leases, clock, confirmation, TargetResolver::resolveTrash, null);
    }

    /** {@code trash} and {@code wire} are test seams (GreenMail reports no SPECIAL-USE; scripted refusals). */
    PurgeMessagesTool(WritePreflight preflight, StoreLeases leases, Clock clock, PurgeConfirmation confirmation,
            DeleteMessagesTool.TrashResolver trash, PurgeStep.Wire wire) {
        this.preflight = Objects.requireNonNull(preflight, "preflight");
        this.leases = Objects.requireNonNull(leases, "leases");
        this.runner = new FolderGroupRunner(Objects.requireNonNull(clock, "clock"));
        this.confirmation = Objects.requireNonNull(confirmation, "confirmation");
        this.trash = Objects.requireNonNull(trash, "trash");
        this.wire = wire == null ? PurgeStep.LIVE : wire;
    }

    @Override
    @SuppressWarnings("unchecked")
    public ToolOutcome handle(ToolCallContext ctx, Map<String, Object> args) {
        long start = System.nanoTime();
        String rid = ctx.requestId();
        String account = (String) args.get("account");
        List<String> handleList = List.copyOf((List<String>) args.get("handles"));
        WritePreflight.Result checked = preflight.check(ctx, account, handleList, null, a -> {
            // the operator's gate is read from the account record per call; both checks without I/O
            if (!a.account().allowPurge()) {
                return ToolError.purgeDisabled(rid);
            }
            return confirmation.available(ctx) ? null : ToolError.confirmationUnavailable(rid);
        });
        if (!(checked instanceof WritePreflight.Result.Ok ok)) {
            return failure(((WritePreflight.Result.Failed) checked).error(), null, start);
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
                    return failure(mapped.error(), null, start);
                }
            });
        } catch (MessagingException | RuntimeException e) {
            return failure(ImapErrorMapper.map(e, rid).error(), null, start);
        }
    }

    /** Steps 7 to 10 on a leased connection; package-private so tests can force the connection's capabilities. */
    ToolOutcome onLease(ImapLease lease, ToolCallContext ctx, WritePreflight.Result.Ok ok, String account,
            List<String> handleList) throws MessagingException {
        return run(lease, ctx, ok, account, handleList, System.nanoTime());
    }

    private ToolOutcome run(ImapLease lease, ToolCallContext ctx, WritePreflight.Result.Ok ok, String account,
            List<String> handleList, long start) throws MessagingException {
        String rid = ctx.requestId();
        Optional<ToolError> gate = lease.capabilities().requireUidplus(rid);
        if (gate.isPresent()) {
            return failure(gate.get(), null, start);
        }
        FolderResolver.Resolution target = trash.resolve(lease, rid);
        if (target.error() != null && target.error().code() != ErrorCode.FOLDER_NOT_FOUND) {
            return failure(target.error(), null, start);
        }
        // no \Trash role: every handle is out of scope, so the first position is the one reported
        String trashName = target.error() != null ? null : target.fullName();
        int firstOutside = -1;
        for (WritePreflight.Group g : ok.groups()) {
            if (!g.folder().equals(trashName)) {
                for (WritePreflight.Item i : g.items()) {
                    firstOutside = firstOutside < 0 ? i.index() : Math.min(firstOutside, i.index());
                }
            }
        }
        if (firstOutside >= 0) {
            return failure(ToolError.purgeScope("handles[" + firstOutside + "]", rid), null, start);
        }
        List<WritePreflight.Item> items = new ArrayList<>();
        ok.groups().forEach(g -> items.addAll(g.items()));
        PurgeConfirmation.Decision decision = confirmation.confirm(ctx,
                new PurgeConfirmation.Request(ok.account(), lease, trashName, items));
        if (decision instanceof PurgeConfirmation.Decision.Expired) {
            return failure(ToolError.timeout(rid), null, start);
        }
        if (decision instanceof PurgeConfirmation.Decision.Refused refused) {
            // the handles are verified: audit the refusal per item (SPEC 5.3); not fail-closed, nothing changes
            ctx.audit().recordRefusal(items.stream()
                    .map(i -> new ItemRef(i.ref().folder(), i.ref().uidValidity(), i.ref().uid())).toList(),
                    ErrorCode.PERMISSION_DENIED.name(), refused.reason().name().toLowerCase(java.util.Locale.ROOT));
            return failure(ToolError.notConfirmed(refused.reason(), rid), refused.reason(), start);
        }
        String how = ((PurgeConfirmation.Decision.Confirmed) decision).confirmation();
        ctx.audit().confirmation(how); // recorded with the audit rows the runner writes before the first deletion
        List<BulkResult.Item> results = runner.run(lease, ctx, ok.groups(), FolderGroupRunner.AbsentStatus.ALREADY_GONE,
                new PurgeStep(trashName, rid, wire));
        BulkResult result = BulkResult.purgeInInputOrder(account, how, handleList, results);
        PurgeAudit.result(result, ok.groups().size(), Duration.ofNanos(System.nanoTime() - start));
        return new ToolOutcome.Success(result.toMap());
    }

    private static ToolOutcome failure(ToolError e, ToolError.ElicitationReason elicitation, long start) {
        PurgeAudit.error(e.code(), elicitation, Duration.ofNanos(System.nanoTime() - start));
        return new ToolOutcome.Failure(e);
    }
}
