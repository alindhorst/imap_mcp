package de.alexanderlindhorst.imapmcp.core.tools.write;

import de.alexanderlindhorst.imapmcp.core.error.ErrorCode;
import java.time.Duration;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

/**
 * The one INFO line per write call (SPEC 5.6), also for a whole-call error. Hashed tenant, account and tool come
 * from the executor's MDC; the line adds tool and request id explicitly. Only closed values are logged: no
 * handles, folder names, keywords or upstream text (those are never passed in).
 */
public final class WriteCallLog {

    private static final Logger LOG = LoggerFactory.getLogger(WriteCallLog.class);

    private WriteCallLog() {
    }

    /** A bulk tool that produced a per-item result. {@code copyFallback}: any folder group used COPY + flag + UID EXPUNGE. */
    public static void bulk(String tool, BulkResult result, int groups, boolean copyFallback, Duration duration) {
        Map<ItemStatus, Integer> counts = result.counts();
        StringBuilder sb = new StringBuilder();
        counts.forEach((s, n) -> sb.append(' ').append(s.wire()).append('=').append(n));
        boolean unknown = counts.get(ItemStatus.UNKNOWN) > 0;
        boolean complete = result.results().stream().allMatch(i -> i.status().isSuccess());
        String outcome = complete ? "complete" : unknown ? "unknown" : "partial";
        String path = switch (result.kind()) {
            case MOVE, DELETE -> copyFallback ? "copy_fallback" : "move";
            case COPY -> "copy";
            case SET_FLAGS -> "store";
            case PURGE -> "expunge";
        };
        LOG.info("write call tool={} request_id={} duration_ms={} groups={} path={} outcome={}{}", tool, MDC.get("req"),
                duration.toMillis(), groups, path, outcome, sb);
    }

    public static void createFolder(String tool, boolean created, boolean subscribed, Duration duration) {
        LOG.info("write call tool={} request_id={} duration_ms={} created={} subscribed={}", tool, MDC.get("req"),
                duration.toMillis(), created, subscribed);
    }

    /** The whole call failed; no counts. */
    public static void error(String tool, ErrorCode code, Duration duration) {
        LOG.info("write call tool={} request_id={} duration_ms={} error_code={}", tool, MDC.get("req"),
                duration.toMillis(), code.name());
    }
}
