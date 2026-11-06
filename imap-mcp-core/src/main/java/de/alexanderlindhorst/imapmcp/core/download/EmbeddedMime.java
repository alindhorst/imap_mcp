package de.alexanderlindhorst.imapmcp.core.download;

/** The fixed MIME type list of the embedded resource block (SPEC 4). */
public final class EmbeddedMime {

    public static final String FALLBACK = "application/octet-stream";

    private EmbeddedMime() {
    }

    public static String of(String contentType) {
        throw new UnsupportedOperationException();
    }
}
