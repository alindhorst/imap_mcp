package de.alexanderlindhorst.imapmcp.core.mime;

/**
 * The text part chosen as message body. {@code sectionPath} is the IMAP section for {@code BODY.PEEK[...]}
 * ("1" for a non-multipart message); {@code charsetParam} is the raw Content-Type charset (nullable, untrusted).
 */
public record SelectedPart(String sectionPath, String contentType, String transferEncoding, String charsetParam,
        long encodedSize) {
}
