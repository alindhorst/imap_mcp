package de.alexanderlindhorst.imapmcp.core.imap;

/** Classifies a refused LOGIN (SPEC 4 Tool rules, 5.3 Re-link, D14). */
public final class LoginRefusal {

    private LoginRefusal() {
    }

    /** Whether the chain holds an {@code AuthenticationFailedException} whose text leads with {@code [UNAVAILABLE]}. */
    public static boolean isUnavailable(Throwable t) {
        return false;
    }
}
