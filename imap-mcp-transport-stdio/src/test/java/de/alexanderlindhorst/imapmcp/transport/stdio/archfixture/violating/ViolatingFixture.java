package de.alexanderlindhorst.imapmcp.transport.stdio.archfixture.violating;

import org.eclipse.jetty.archfixture.FakeJettyType;

/** Deliberately violates the stdio rules; excluded from the real scan via {@code ExcludeFixtures}. */
public class ViolatingFixture {
    FakeJettyType server;
}
