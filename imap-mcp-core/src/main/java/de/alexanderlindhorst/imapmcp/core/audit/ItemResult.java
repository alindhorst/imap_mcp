package de.alexanderlindhorst.imapmcp.core.audit;

/** Closed values only: the item status on the wire ({@code ok}, {@code failed}, ...) and an error code or null. */
public record ItemResult(String status, String errorCode) {
}
