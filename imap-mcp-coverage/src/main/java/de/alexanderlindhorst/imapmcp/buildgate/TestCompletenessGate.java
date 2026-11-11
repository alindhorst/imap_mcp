package de.alexanderlindhorst.imapmcp.buildgate;

import java.nio.file.Path;
import java.util.List;

/** Stub for the red commit. */
public final class TestCompletenessGate {

    private TestCompletenessGate() {
    }

    /** One allowlist entry: {@code class#method | reason | condition}; method {@code *} covers every test of the class. */
    public record AllowedSkip(String className, String method, String reason, String condition) {
    }

    public record Config(Path root, long sessionStartMillis, boolean skipTests, boolean skipITs,
            boolean mavenTestSkip, boolean partialBuild) {
    }

    public record Result(List<String> violations, String summary) {
        public boolean ok() {
            throw new UnsupportedOperationException();
        }
    }

    public static List<AllowedSkip> parseAllowlist(String text) {
        throw new UnsupportedOperationException();
    }

    public static Result check(Config config, List<AllowedSkip> allowlist) {
        throw new UnsupportedOperationException();
    }
}
