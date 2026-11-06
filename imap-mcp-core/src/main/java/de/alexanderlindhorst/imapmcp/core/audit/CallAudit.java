package de.alexanderlindhorst.imapmcp.core.audit;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The audit record of one mutating tool call (SPEC 5.3), driven by the write tools: {@link #begin} before the
 * first {@code tryBeginMutation()} (fail-closed: a failed write throws {@link AuditWriteException} and the tool
 * has changed nothing), {@link #complete} or {@link #completeAborted} afterwards, once. A failed completion is an
 * ERROR line with closed values and the item counts, never an exception: the mutation has happened and the result
 * is still returned. Disabled (no log attached) every method does nothing.
 */
public final class CallAudit {

    private static final Logger LOG = LoggerFactory.getLogger(CallAudit.class);
    private static final CallAudit DISABLED = new CallAudit(null, () -> null);

    private final AuditLog log;
    private final Supplier<CallInfo> call;
    private AuditLog.Entry entry;
    private int itemCount;
    private boolean completed;
    private boolean attempted;
    private boolean failureRecorded;
    private String confirmation;
    private String tool = "";
    private String requestId = "";

    public CallAudit(AuditLog log, Supplier<CallInfo> call) {
        this.log = log;
        this.call = Objects.requireNonNull(call, "call");
    }

    public static CallAudit disabled() {
        return DISABLED;
    }

    public boolean enabled() {
        return log != null && log != AuditLog.NONE;
    }

    /**
     * Writes the rows (one per item, or one when there are none). Only the first call per call does anything, and
     * none after {@link #recordFailure} (the executor's deadline row is written; a late worker adds no item rows).
     */
    public synchronized void begin(List<ItemRef> items) throws AuditWriteException {
        if (!enabled() || entry != null || failureRecorded) {
            return;
        }
        attempted = true;
        CallInfo info = call.get().withConfirmation(confirmation);
        entry = log.begin(info, List.copyOf(items));
        itemCount = items.size();
        tool = info.tool();
        requestId = info.requestId();
    }

    /** The {@code confirmation} value of a purge ({@code elicited}, {@code session_approval}); kept until completion. */
    public synchronized void confirmation(String confirmation) {
        this.confirmation = confirmation;
    }

    /** {@code items} in the order of the refs given to {@link #begin}. */
    public synchronized void complete(List<ItemResult> items, String result, String errorCode) {
        if (entry == null || completed) {
            return;
        }
        completed = true;
        try {
            entry.complete(new Completion(List.copyOf(items), result, errorCode, confirmation));
        } catch (RuntimeException e) {
            Map<String, Integer> counts = new LinkedHashMap<>();
            items.forEach(i -> counts.merge(i.status(), 1, Integer::sum));
            StringBuilder sb = new StringBuilder();
            counts.forEach((s, n) -> sb.append(' ').append(s).append('=').append(n));
            LOG.error("audit completion failed tool={} request_id={} result={}{}", tool, requestId, result, sb);
        }
    }

    /**
     * The call ended without a result (failure, cancel or deadline) after {@link #begin}: all items {@code unknown}
     * if a mutation began, else {@code not_attempted}, each with the whole-call {@code errorCode} (the code on the wire).
     */
    public synchronized void completeAborted(boolean mutationStarted, String errorCode) {
        if (entry == null || completed) {
            return;
        }
        String status = mutationStarted ? "unknown" : "not_attempted";
        complete(java.util.Collections.nCopies(itemCount, new ItemResult(status, errorCode)),
                mutationStarted ? "unknown" : "failed", errorCode);
    }

    /**
     * A whole-call error of a call with a resolved account that ended before any row was begun (read-only account, invalid argument or
     * handle, ...): one item-less call row with the error code. Not fail-closed: nothing was changed, so a failed
     * write is one ERROR line and the original error stays. No-op once rows were begun or a begin was attempted.
     */
    public synchronized void recordFailure(String errorCode) {
        if (!enabled() || attempted) {
            return;
        }
        CallInfo info = call.get().withConfirmation(confirmation);
        if (info.account() == null) {
            return; // no resolved account: not covered (SPEC 5.3, open decisions D10, D11)
        }
        attempted = true;
        failureRecorded = true;
        try {
            log.begin(info, List.of()).complete(new Completion(List.of(), "failed", errorCode, confirmation));
        } catch (RuntimeException e) {
            LOG.error("audit record failed tool={} request_id={} error_code={}", info.tool(), info.requestId(),
                    errorCode);
        }
    }

    /**
     * A refusal after the items were verified (a purge that was not confirmed): one row per item with the code, no
     * item status and the confirmation value. Not fail-closed, like {@link #recordFailure}.
     */
    public synchronized void recordRefusal(List<ItemRef> items, String errorCode, String confirmation) {
        if (!enabled() || attempted) {
            return;
        }
        this.confirmation = confirmation;
        try {
            begin(items);
        } catch (RuntimeException e) {
            CallInfo info = call.get();
            LOG.error("audit record failed tool={} request_id={} error_code={}", info.tool(), info.requestId(),
                    errorCode);
            return;
        }
        complete(java.util.Collections.nCopies(items.size(), new ItemResult(null, null)), "failed",
                errorCode);
    }
}
