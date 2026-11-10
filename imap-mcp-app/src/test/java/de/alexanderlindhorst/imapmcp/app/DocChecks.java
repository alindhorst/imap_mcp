package de.alexanderlindhorst.imapmcp.app;

import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Checks shared by the doc tests ({@link TutorialTest}, {@link ReadmeTest}). */
final class DocChecks {

    private static final Pattern SECRET_LIKE = Pattern.compile("\\b(?![A-Z_]+\\b)[A-Za-z0-9_-]{32,}");

    private DocChecks() {
    }

    /** Removes only the {@code #fragment} of markdown link targets; path and URL parts stay. */
    static String stripLinkFragments(String doc) {
        return doc.replaceAll("(\\]\\([^)\\s#]*)#[^)\\s]*\\)", "$1)");
    }

    /** The first secret-like token outside link fragments, if any. */
    static Optional<String> findSecretLikeToken(String doc) {
        Matcher m = SECRET_LIKE.matcher(stripLinkFragments(doc));
        return m.find() ? Optional.of(m.group()) : Optional.empty();
    }
}
