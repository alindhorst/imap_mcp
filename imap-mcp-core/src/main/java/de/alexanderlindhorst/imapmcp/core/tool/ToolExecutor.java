package de.alexanderlindhorst.imapmcp.core.tool;

import de.alexanderlindhorst.imapmcp.core.crypto.LogIds;
import java.time.Duration;

public final class ToolExecutor {

    public static final Duration DEFAULT_DEADLINE = Duration.ofSeconds(90);

    public ToolExecutor(Duration deadline, LogIds logIds) {
    }

    public ToolHandler wrap(String toolName, ToolHandler delegate) {
        throw new UnsupportedOperationException();
    }
}
