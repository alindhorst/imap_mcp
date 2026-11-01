package de.alexanderlindhorst.imapmcp.core.mime;

import java.nio.charset.Charset;

/** Maps a Content-Type charset parameter to a Java charset, falling back to UTF-8. */
public final class CharsetResolver {

    private CharsetResolver() {
    }

    public static Charset resolve(String charsetParam) {
        return null;
    }
}
