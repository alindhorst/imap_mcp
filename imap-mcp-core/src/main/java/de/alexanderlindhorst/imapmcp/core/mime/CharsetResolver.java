package de.alexanderlindhorst.imapmcp.core.mime;

import jakarta.mail.internet.MimeUtility;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;

/** Maps a Content-Type charset parameter to a Java charset, falling back to UTF-8. */
public final class CharsetResolver {

    private CharsetResolver() {
    }

    /** The charset to decode with; its {@link Charset#name()} is the canonical name to report. */
    public static Charset resolve(String charsetParam) {
        if (charsetParam == null || charsetParam.isBlank()) {
            return StandardCharsets.UTF_8;
        }
        try {
            return Charset.forName(MimeUtility.javaCharset(charsetParam.strip()));
        } catch (IllegalArgumentException e) { // illegal name or unsupported charset
            return StandardCharsets.UTF_8;
        }
    }
}
