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

    /** Writes the rows (one per item, or one when there are none). Only the first call per call does anything. */
    public synchronized void begin(List<ItemRef> items) throws AuditWriteException {
        if (!enabled() || entry != null) {
            return;
        }
        CallInfo info = call.get();
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

    /** The call ended without a result (failure or cancel): all items {@code unknown} if a mutation began, else {@code not_attempted}. */
    public synchronized void completeAborted(boolean mutationStarted, String errorCode) {
        if (entry == null || completed) {
            return;
        }
        String status = mutationStarted ? "unknown" : "not_attempted";
        complete(java.util.Collections.nCopies(itemCount, new ItemResult(status, null)),
                mutationStarted ? "unknown" : "failed", null);
    }

    /** Stub. */
    public synchronized void recordFailure(String errorCode) {
    }

    /** Stub. */
    public synchronized void recordRefusal(List<ItemRef> items, String errorCode, String confirmation) {
    }
}
