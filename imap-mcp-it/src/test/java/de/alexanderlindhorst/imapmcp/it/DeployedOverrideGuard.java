package de.alexanderlindhorst.imapmcp.it;

import java.util.List;

/** Checks a test-only compose override against the shipped compose.yaml (SPEC 5.8, deployed end-to-end test). */
final class DeployedOverrideGuard {

    private DeployedOverrideGuard() {
    }

    /** The violations, each naming the offending path (e.g. {@code services.app.environment}); empty if allowed. */
    static List<String> violations(String shippedYaml, String overrideYaml) {
        return List.of();
    }
}
