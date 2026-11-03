package de.alexanderlindhorst.imapmcp.core.tools.write;

import de.alexanderlindhorst.imapmcp.core.error.ErrorCode;
import java.time.Duration;

/** Stub. */
public final class WriteCallLog {
    private WriteCallLog() {
    }

    public static void bulk(String tool, BulkResult result, int groups, boolean copyFallback, Duration duration) {
    }

    public static void createFolder(String tool, boolean created, boolean subscribed, Duration duration) {
    }

    public static void error(String tool, ErrorCode code, Duration duration) {
    }
}
