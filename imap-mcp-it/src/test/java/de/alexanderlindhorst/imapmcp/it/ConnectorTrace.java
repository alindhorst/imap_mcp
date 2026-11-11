package de.alexanderlindhorst.imapmcp.it;

import java.net.URI;
import java.util.List;
import java.util.Map;

/** The connector client's own trace in the #434 fixture format. */
final class ConnectorTrace {

    ConnectorTrace(String mcpAuthority, String idpAuthority) {
    }

    synchronized void record(String method, URI uri, Map<String, List<String>> requestHeaders, int status,
            boolean wwwAuthenticate, long startNanos) {
        throw new UnsupportedOperationException();
    }

    synchronized ConnectorSessionFixture fixture() {
        throw new UnsupportedOperationException();
    }

    String text() {
        throw new UnsupportedOperationException();
    }
}
