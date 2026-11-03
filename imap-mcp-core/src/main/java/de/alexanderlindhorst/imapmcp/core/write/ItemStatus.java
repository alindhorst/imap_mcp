package de.alexanderlindhorst.imapmcp.core.write;

import java.util.Collections;
import java.util.EnumSet;
import java.util.Set;

/** Per-item outcome of a bulk write tool (SPEC 4, v2 write contract). Declaration order is the summary order. */
public enum ItemStatus {
    OK("ok"),
    ALREADY_IN_TARGET("already_in_target"),
    ALREADY_GONE("already_gone"),
    NOT_FOUND("not_found"),
    STALE_REFERENCE("stale_reference"),
    FAILED("failed"),
    NOT_ATTEMPTED("not_attempted"),
    UNKNOWN("unknown");

    /** The statuses that count as success (the write log line's {@code outcome}). */
    public static final Set<ItemStatus> SUCCESS = Collections.unmodifiableSet(EnumSet.of(OK, ALREADY_IN_TARGET, ALREADY_GONE));

    private final String wire;

    ItemStatus(String wire) {
        this.wire = wire;
    }

    public String wire() {
        return wire;
    }

    public boolean isSuccess() {
        return SUCCESS.contains(this);
    }
}
