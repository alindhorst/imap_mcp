package de.alexanderlindhorst.imapmcp.core.mime;

/** HTML to plain text for untrusted mail bodies (SPEC 4, 5.3). Pure, deterministic, no network. */
public final class HtmlToText {

    private HtmlToText() {
    }

    public static String convert(String html) {
        throw new UnsupportedOperationException("not implemented");
    }
}
