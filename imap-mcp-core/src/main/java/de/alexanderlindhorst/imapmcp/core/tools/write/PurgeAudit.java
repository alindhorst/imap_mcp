package de.alexanderlindhorst.imapmcp.core.tools.write;

import de.alexanderlindhorst.imapmcp.core.error.ErrorCode;
import de.alexanderlindhorst.imapmcp.core.error.ToolError;
import java.time.Duration;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

/**
 * The single-tenant record of a permanent deletion (SPEC 5.6): one INFO line per {@code purge_messages} call on the
 * logger {@code imapmcp.audit}, which the logging configuration pins to INFO so that a higher root level does not
 * hide it. Closed values only: counts, the error code, the {@code confirmation} value; never handles, folder names,
 * the account label, the elicitation message or the answer content.
 */
public final class PurgeAudit {

    public static final String LOGGER = "imapmcp.audit";
    private static final Logger LOG = LoggerFactory.getLogger(LOGGER);

    private PurgeAudit() {
    }

    /** A call that produced per-item results. */
    public static void result(BulkResult result, int groups, Duration duration) {
        Map<ItemStatus, Integer> counts = result.counts();
        StringBuilder sb = new StringBuilder();
        counts.forEach((s, n) -> sb.append(' ').append(s.wire()).append('=').append(n));
        boolean unknown = counts.get(ItemStatus.UNKNOWN) > 0;
        boolean complete = result.results().stream().allMatch(i -> i.status().isSuccess());
        String outcome = complete ? "complete" : unknown ? "unknown" : "partial";
        LOG.info("purge call tool={} request_id={} duration_ms={} groups={} path=expunge confirmation={} outcome={}{}",
                PurgeMessagesTool.NAME, MDC.get("req"), duration.toMillis(), groups, result.confirmation(), outcome,
                sb);
    }

    /** A whole-call error; {@code confirmation} is the {@code details.elicitation} value of a not_confirmed refusal. */
    public static void error(ErrorCode code, ToolError.ElicitationReason elicitation, Duration duration) {
        if (elicitation == null) {
            LOG.info("purge call tool={} request_id={} duration_ms={} error_code={}", PurgeMessagesTool.NAME,
                    MDC.get("req"), duration.toMillis(), code.name());
        } else {
            LOG.info("purge call tool={} request_id={} duration_ms={} error_code={} confirmation={}",
                    PurgeMessagesTool.NAME, MDC.get("req"), duration.toMillis(), code.name(),
                    elicitation.name().toLowerCase(java.util.Locale.ROOT));
        }
    }
}
