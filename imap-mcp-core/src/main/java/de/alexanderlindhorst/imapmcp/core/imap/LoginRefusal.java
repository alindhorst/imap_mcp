package de.alexanderlindhorst.imapmcp.core.imap;

import jakarta.mail.AuthenticationFailedException;
import java.util.Locale;

/**
 * Classifies a refused LOGIN (SPEC 4 Tool rules, 5.3 Re-link, D14). Angus reports a LOGIN answered {@code NO} as an
 * {@link AuthenticationFailedException} carrying the server's text; the one matcher here decides for the error
 * mapping, the re-link counting and the auth-failure metric alike. The text is only inspected, never logged.
 */
public final class LoginRefusal {

    private static final int MAX_DEPTH = 8;

    private LoginRefusal() {
    }

    /**
     * Whether the first {@link AuthenticationFailedException} in the cause chain has a text that begins with the
     * response code {@code [UNAVAILABLE]} (RFC 5530; case-insensitive, after leading whitespace): a temporary
     * server problem, not a credential refusal.
     */
    public static boolean isUnavailable(Throwable t) {
        AuthenticationFailedException refusal = refusal(t);
        if (refusal == null || refusal.getMessage() == null) {
            return false;
        }
        return refusal.getMessage().strip().toUpperCase(Locale.ROOT).startsWith("[UNAVAILABLE]");
    }

    /** A LOGIN refused for its credentials: counts toward a re-link and as an auth failure. */
    public static boolean isCredentialRefusal(Throwable t) {
        return refusal(t) != null && !isUnavailable(t);
    }

    private static AuthenticationFailedException refusal(Throwable t) {
        for (int depth = 0; t != null && depth < MAX_DEPTH; depth++, t = t.getCause()) {
            if (t instanceof AuthenticationFailedException afe) {
                return afe;
            }
        }
        return null;
    }
}
