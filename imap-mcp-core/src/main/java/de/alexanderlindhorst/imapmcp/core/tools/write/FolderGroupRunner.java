package de.alexanderlindhorst.imapmcp.core.tools.write;

import de.alexanderlindhorst.imapmcp.core.audit.CallAudit;
import de.alexanderlindhorst.imapmcp.core.audit.ItemRef;
import de.alexanderlindhorst.imapmcp.core.audit.ItemResult;
import de.alexanderlindhorst.imapmcp.core.error.ToolError;
import de.alexanderlindhorst.imapmcp.core.imap.pool.ImapLease;
import de.alexanderlindhorst.imapmcp.core.tool.ToolCallContext;
import jakarta.mail.Message;
import jakarta.mail.MessagingException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import org.eclipse.angus.mail.imap.IMAPFolder;

/**
 * Runs the per source-folder groups of a bulk write call, sequentially on the call's lease (SPEC 4 check order
 * step 9, 5.4, 5.5). Per group: existence by LIST ({@code stale_reference} {@code folder_missing}), SELECT read-write,
 * UIDVALIDITY compare ({@code stale_reference} {@code uidvalidity_changed}), existence pre-check by UID FETCH
 * (absent UIDs become {@code already_gone} or {@code not_found}), then the tool-specific {@link MutationStep} with
 * the present UIDs only; the folder is closed with {@code close(false)} before the next group. A group never
 * selects the target folder and an empty present set never reaches the step, so no empty UID set is sent.
 *
 * <p>The step calls {@link Gate#beforeWrite()} before every mutating command. For the first command of a group the
 * gate checks the fixed 15 s budget against the call deadline (less remaining: the rest of that group (items the pre-check
 * already settled keep their status) and all later groups are {@code not_attempted}, a normal result); for the first command of the call it then
 * calls {@link ToolCallContext#tryBeginMutation()}, so the mark is never set by the pre-check. If that returns
 * false (the call was cancelled or its deadline decided) nothing is written and the group unwinds like a spent budget
 * ({@code not_attempted}); the executor's plain {@code TIMEOUT} is the call result. A started step is never
 * interrupted by the budget.
 *
 * <p>Failures: a NO or {@code [READ-ONLY]} (mapped by {@link WriteResponses}) fails the items of that group and
 * leaves the lease intact; the next group runs. Anything else from the connection marks the lease broken: before
 * the mark it is rethrown (the v1 whole-call error), after it the finished groups keep their results, the in-flight
 * group is {@code unknown} (its first command was already released) or {@code not_attempted} (not yet), and later
 * groups are {@code not_attempted}. Nothing is logged here; no handle, folder name or server text leaves the class
 * except in the mapped, sanitised {@link ToolError}.
 *
 * <p>Audit (SPEC 5.3): when the call has an audit log, the rows of all items are written first ({@code begin},
 * fail-closed: nothing is read or changed if that throws) and completed with the item statuses afterwards.
 */
public final class FolderGroupRunner {

    /** Fixed write budget before each group's first mutating command (SPEC 5.5). */
    public static final Duration WRITE_BUDGET = Duration.ofSeconds(15);

    /** Status of a UID that the pre-check did not find: move/delete {@code ALREADY_GONE}, copy/set_flags {@code NOT_FOUND}. */
    public enum AbsentStatus {
        ALREADY_GONE, NOT_FOUND
    }

    /** Points at which tests inject faults; production hook does nothing. */
    enum HookPoint {
        /** Before the command is released; the call may not yet be marked. */
        BEFORE_WRITE,
        /** After the mark, command released, answer not yet read. */
        AFTER_WRITE,
        /** After a group finished, before the next one starts (command index -1). */
        BETWEEN_GROUPS
    }

    /** Package-private fault-injection seam (SPEC 5.8): throws to simulate a failure at a defined point. */
    interface StepHook {
        void at(HookPoint point, int groupIndex, int commandIndex) throws MessagingException;
    }

    /** Handed to the mutation step; the step calls it before each mutating command it sends. */
    public interface Gate {
        void beforeWrite() throws MessagingException;
    }

    /**
     * The tool-specific mutating step of one group (e.g. UID STORE, or COPY then STORE then UID EXPUNGE).
     * Receives only present UIDs, returns one item per present item.
     */
    @FunctionalInterface
    public interface MutationStep {
        List<BulkResult.Item> apply(IMAPFolder folder, List<WritePreflight.Item> present, Gate gate)
                throws MessagingException;
    }

    /** Internal: the budget ran out, or the call was cancelled, before a mutating command; nothing was written. */
    private static final class BudgetExhausted extends RuntimeException {
        BudgetExhausted() {
            super(null, null, false, false);
        }
    }

    /** Per-group progress needed to classify a failure. */
    private static final class GroupState {
        final int index;
        boolean written;
        int commands;
        List<BulkResult.Item> known = new ArrayList<>();

        GroupState(int index) {
            this.index = index;
        }
    }

    private final Clock clock;
    private final StepHook hook;

    public FolderGroupRunner(Clock clock) {
        this(clock, (p, g, c) -> { });
    }

    FolderGroupRunner(Clock clock, StepHook hook) {
        this.clock = Objects.requireNonNull(clock, "clock");
        this.hook = Objects.requireNonNull(hook, "hook");
    }

    /**
     * @return one item per input item of all groups (not in input order; the tool orders them with
     *         {@link BulkResult#inInputOrder})
     * @throws MessagingException a connection-level failure before the first mutating command
     */
    public List<BulkResult.Item> run(ImapLease lease, ToolCallContext ctx, List<WritePreflight.Group> groups,
            AbsentStatus absent, MutationStep step) throws MessagingException {
        return run(lease, ctx, groups, absent, step, List.of());
    }

    /** An item the tool settled itself (no IMAP command), still audited with its status. */
    public record Settled(WritePreflight.Item item, BulkResult.Item result) {
    }

    /**
     * As above; {@code settled} items are audited in the same batch (after the grouped ones) with their status. A
     * failure leaves the begun rows to the executor, which completes them with the mapped whole-call code.
     */
    public List<BulkResult.Item> run(ImapLease lease, ToolCallContext ctx, List<WritePreflight.Group> groups,
            AbsentStatus absent, MutationStep step, List<Settled> settled) throws MessagingException {
        Objects.requireNonNull(lease, "lease");
        Objects.requireNonNull(ctx, "ctx");
        CallAudit audit = ctx.audit();
        List<WritePreflight.Item> flat = new ArrayList<>(groups.stream().flatMap(g -> g.items().stream()).toList());
        settled.forEach(s -> flat.add(s.item()));
        if (!flat.isEmpty()) {
            // fail-closed (SPEC 5.3, D5): a failed write throws here, before any IMAP I/O and any mutation
            audit.begin(flat.stream()
                    .map(i -> new ItemRef(i.ref().folder(), i.ref().uidValidity(), i.ref().uid())).toList());
        }
        List<BulkResult.Item> out = runGroups(lease, ctx, groups, absent, step);
        if (!flat.isEmpty()) {
            List<BulkResult.Item> all = new ArrayList<>(out);
            settled.forEach(s -> all.add(s.result()));
            audit.complete(auditResults(flat, all), auditResult(all), null);
        }
        return out;
    }

    /** One result per begun item, in begin order (items of the call share a handle at most by repetition). */
    private static List<ItemResult> auditResults(List<WritePreflight.Item> flat, List<BulkResult.Item> out) {
        Map<String, BulkResult.Item> byHandle = new HashMap<>();
        out.forEach(i -> byHandle.putIfAbsent(i.handle(), i));
        return flat.stream().map(i -> {
            BulkResult.Item r = byHandle.get(i.handle());
            if (r == null) {
                return new ItemResult(ItemStatus.NOT_ATTEMPTED.wire(), null);
            }
            return new ItemResult(r.status().wire(), r.error() == null ? null : r.error().code().name());
        }).toList();
    }

    private static String auditResult(List<BulkResult.Item> out) {
        if (out.stream().anyMatch(i -> i.status() == ItemStatus.UNKNOWN)) {
            return "unknown";
        }
        return out.stream().allMatch(i -> i.status().isSuccess()) ? "complete" : "partial";
    }

    private List<BulkResult.Item> runGroups(ImapLease lease, ToolCallContext ctx, List<WritePreflight.Group> groups,
            AbsentStatus absent, MutationStep step) throws MessagingException {
        List<BulkResult.Item> out = new ArrayList<>();
        boolean stopped = false;
        for (int gi = 0; gi < groups.size(); gi++) {
            WritePreflight.Group group = groups.get(gi);
            if (stopped) {
                group.items().forEach(i -> out.add(BulkResult.Item.notAttempted(i.handle())));
                continue;
            }
            GroupState st = new GroupState(gi);
            try {
                if (gi > 0) {
                    hook.at(HookPoint.BETWEEN_GROUPS, gi, -1);
                }
                out.addAll(runGroup(lease, ctx, group, absent, step, st));
            } catch (BudgetExhausted e) {
                fill(out, group, st, i -> BulkResult.Item.notAttempted(i.handle()));
                stopped = true;
            } catch (MessagingException e) {
                Optional<ToolError> mapped = WriteResponses.map(e, null, ctx.requestId());
                if (mapped.isPresent()) {
                    fill(out, group, st, i -> BulkResult.Item.failed(i.handle(), mapped.get()));
                    continue;
                }
                lease.markBroken();
                if (!ctx.mutationStarted()) {
                    throw e;
                }
                if (st.written) {
                    fill(out, group, st, i -> BulkResult.Item.unknown(i.handle()));
                } else {
                    fill(out, group, st, i -> BulkResult.Item.notAttempted(i.handle()));
                }
                stopped = true;
            } finally {
                lease.closeFolder();
            }
        }
        return out;
    }

    /** Adds what the group already settled plus {@code rest} for every other item of it. */
    private static void fill(List<BulkResult.Item> out, WritePreflight.Group group, GroupState st,
            java.util.function.Function<WritePreflight.Item, BulkResult.Item> rest) {
        out.addAll(st.known);
        for (WritePreflight.Item i : group.items()) {
            if (st.known.stream().noneMatch(k -> k.handle().equals(i.handle()))) {
                out.add(rest.apply(i));
            }
        }
    }

    private List<BulkResult.Item> runGroup(ImapLease lease, ToolCallContext ctx, WritePreflight.Group group,
            AbsentStatus absent, MutationStep step, GroupState st) throws MessagingException {
        String rid = ctx.requestId();
        List<WritePreflight.Item> items = group.items();
        if (!lease.store().getFolder(group.folder()).exists()) {
            return staleAll(items, ToolError.StaleReason.FOLDER_MISSING);
        }
        IMAPFolder folder = lease.openReadWrite(group.folder());
        long validity = folder.getUIDValidity();
        // handles of one folder may have been issued at different times: only those of the current validity go on
        List<WritePreflight.Item> current = new ArrayList<>();
        for (WritePreflight.Item i : items) {
            if (i.ref().uidValidity() == validity) {
                current.add(i);
            } else {
                st.known.add(BulkResult.Item.stale(i.handle(), ToolError.StaleReason.UIDVALIDITY_CHANGED));
            }
        }
        items = current;
        if (items.isEmpty()) {
            return st.known;
        }
        long[] uids = items.stream().mapToLong(i -> i.ref().uid()).toArray();
        Message[] found = folder.getMessagesByUID(uids);
        List<WritePreflight.Item> present = new ArrayList<>();
        for (int k = 0; k < items.size(); k++) {
            WritePreflight.Item i = items.get(k);
            if (found != null && k < found.length && found[k] != null) {
                present.add(i);
            } else {
                st.known.add(absent == AbsentStatus.ALREADY_GONE
                        ? BulkResult.Item.alreadyGone(i.handle()) : BulkResult.Item.notFound(i.handle()));
            }
        }
        if (present.isEmpty()) {
            return st.known;
        }
        List<BulkResult.Item> stepItems = step.apply(folder, List.copyOf(present), gate(ctx, st));
        List<BulkResult.Item> all = new ArrayList<>(st.known);
        all.addAll(stepItems);
        return all;
    }

    private static List<BulkResult.Item> staleAll(List<WritePreflight.Item> items, ToolError.StaleReason reason) {
        return items.stream().map(i -> BulkResult.Item.stale(i.handle(), reason)).toList();
    }

    private Gate gate(ToolCallContext ctx, GroupState st) {
        return () -> {
            int command = st.commands++;
            if (!st.written) {
                Optional<Instant> deadline = ctx.deadline();
                if (deadline.isPresent()
                        && Duration.between(clock.instant(), deadline.get()).compareTo(WRITE_BUDGET) < 0) {
                    throw new BudgetExhausted();
                }
            }
            hook.at(HookPoint.BEFORE_WRITE, st.index, command);
            if (!ctx.tryBeginMutation()) { // immediately before the command is released; atomic with the cancel
                throw new BudgetExhausted();
            }
            st.written = true;
            hook.at(HookPoint.AFTER_WRITE, st.index, command);
        };
    }
}
