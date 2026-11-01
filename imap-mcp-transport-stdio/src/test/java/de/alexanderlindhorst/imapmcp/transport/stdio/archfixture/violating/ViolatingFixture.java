package de.alexanderlindhorst.imapmcp.transport.stdio.archfixture.violating;

import org.eclipse.jetty.server.Server;

/** Deliberately violates the stdio rules; excluded from the real scan via {@code ExcludeFixtures}. */
public class ViolatingFixture {
    Server server;
}
