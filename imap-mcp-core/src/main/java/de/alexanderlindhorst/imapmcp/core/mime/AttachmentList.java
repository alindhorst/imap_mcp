package de.alexanderlindhorst.imapmcp.core.mime;

import java.util.List;

/**
 * Attachment metadata for one message. {@code total} is the true count, {@code entries} is capped;
 * {@code hasAttachments} is true if any counted part is not inline (also beyond the cap).
 */
public record AttachmentList(List<Entry> entries, int total, boolean truncated, boolean hasAttachments) {

    /** One attachment; {@code untrusted} holds the email-derived values. */
    public record Entry(String part, long size, boolean inline, Untrusted untrusted) {
    }

    /** Email-derived values: sanitized filename (nullable) and lowercase {@code type/subtype}. */
    public record Untrusted(String filename, String contentType) {
    }
}
