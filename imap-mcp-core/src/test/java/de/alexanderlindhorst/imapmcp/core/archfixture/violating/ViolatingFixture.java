package de.alexanderlindhorst.imapmcp.core.archfixture.violating;

import org.eclipse.jetty.server.Server;

/**
 * Deliberately violates the core architecture rules. Excluded from the real scan via {@code ExcludeFixtures}, so the real
 * guard never scans it; {@code ArchitectureRulesTest} asserts the rules DO fail on it.
 */
public class ViolatingFixture {
    Server server;
}
