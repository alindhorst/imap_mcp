package de.alexanderlindhorst.imapmcp.core.audit;

/** The identity of one message as the audit hash covers it; never stored as such, only hashed (SPEC 5.3). */
public record ItemRef(String folder, long uidValidity, long uid) {
}
