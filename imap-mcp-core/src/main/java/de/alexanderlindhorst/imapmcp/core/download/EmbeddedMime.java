package de.alexanderlindhorst.imapmcp.core.download;

import java.util.Locale;
import java.util.Set;

/**
 * The fixed MIME type list of the embedded resource block (SPEC 4): the normalized content type of the part (lower
 * case, parameters dropped) only when it is listed, otherwise {@code application/octet-stream}. The type comes from
 * the email and is untrusted, so nothing outside the list is ever passed on.
 */
public final class EmbeddedMime {

    public static final String FALLBACK = "application/octet-stream";

    private static final Set<String> ALLOWED = Set.of("application/pdf", "image/png", "image/jpeg", "image/gif",
            "image/webp", "text/plain", "text/csv");

    private EmbeddedMime() {
    }

    public static String of(String contentType) {
        if (contentType == null) {
            return FALLBACK;
        }
        int semi = contentType.indexOf(';');
        String base = (semi < 0 ? contentType : contentType.substring(0, semi)).strip().toLowerCase(Locale.ROOT);
        return ALLOWED.contains(base) ? base : FALLBACK;
    }
}
