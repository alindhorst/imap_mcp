package de.alexanderlindhorst.imapmcp.core.net;

import de.alexanderlindhorst.imapmcp.core.account.MailAccount.Security;
import java.net.InetAddress;
import java.util.List;

/** SSRF policy of {@code http-public} (SPEC 5.3). */
public final class SsrfPolicy {

    public static SsrfPolicy of(List<String> allowedHosts, boolean allowAnyPublicHost,
            List<String> extraBlockedCidrs) {
        throw new UnsupportedOperationException();
    }

    public String normalizeHost(String host) {
        throw new UnsupportedOperationException();
    }

    public String checkEndpoint(String host, int port, Security security) {
        throw new UnsupportedOperationException();
    }

    public boolean isBlocked(InetAddress address) {
        throw new UnsupportedOperationException();
    }
}
