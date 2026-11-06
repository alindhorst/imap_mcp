package de.alexanderlindhorst.imapmcp.core.error;

/**
 * Marker for exceptions that say a dependency of the server (for example the key service) was unreachable. They are an
 * operational condition, not a defect: {@code ToolExecutor} logs them at WARN (class only) instead of ERROR. The
 * tool result stays the generic {@code INTERNAL_ERROR}.
 */
public interface ServiceOutage {
}
