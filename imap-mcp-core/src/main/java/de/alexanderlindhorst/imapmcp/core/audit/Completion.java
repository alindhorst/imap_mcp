package de.alexanderlindhorst.imapmcp.core.audit;

import java.util.List;

/**
 * How an audited call ended. {@code items} follow the order of the refs given to {@link AuditLog#begin} (empty
 * when the call had no items); {@code result} is {@code complete}, {@code partial}, {@code unknown} or
 * {@code failed}; {@code errorCode} and {@code confirmation} are closed values or null.
 */
public record Completion(List<ItemResult> items, String result, String errorCode, String confirmation) {
}
