package de.alexanderlindhorst.imapmcp.core.mime;

/**
 * One window of a message body. Offsets and counts are Unicode code points of the normalized text.
 * {@code totalChars} and {@code nextOffset} are null when unknown / when there is no further window.
 */
public record BodyWindow(String contentType, boolean convertedFromHtml, String charset, long offset,
        int returnedChars, Long totalChars, String text, boolean truncated, Long nextOffset,
        boolean sourceTruncated) {
}
