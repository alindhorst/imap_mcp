package de.alexanderlindhorst.imapmcp.it;

import de.alexanderlindhorst.imapmcp.core.net.AddressBlocklist;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.Arrays;
import java.util.List;

/**
 * Candidate backend subnets of the deployed stack and the recognition of an engine's "subnet in use" error. Both
 * defaults are IPv4 /28 blocks of AS112 space, outside every blocked range of SPEC 5.3.
 */
final class DeployedSubnets {

    static final String PROPERTY = "imapmcp.it.backendSubnets";
    private static final String DEFAULTS = "192.31.196.0/28,192.175.48.0/28";

    private DeployedSubnets() {
    }

    /** Podman: "subnet X is already used on the host or by another config"; Docker: "Pool overlaps with other one". */
    static boolean isOverlap(String message) {
        if (message == null) {
            return false;
        }
        String m = message.toLowerCase(java.util.Locale.ROOT);
        return m.contains("pool overlaps") || (m.contains("subnet") && m.contains("already used"));
    }

    static List<String> candidates(String property) {
        String value = property == null || property.isBlank() ? DEFAULTS : property;
        return Arrays.stream(value.split(",")).map(String::strip).filter(s -> !s.isEmpty()).toList();
    }

    /** The first and the last address of the subnet must not be blocked by the production blocklist. */
    static void requireUsable(String cidr) {
        String[] parts = cidr.split("/");
        if (parts.length != 2) {
            throw new IllegalArgumentException("not a CIDR: " + cidr);
        }
        int prefix = Integer.parseInt(parts[1]);
        if (prefix < 24 || prefix > 29) {
            throw new IllegalArgumentException("prefix outside /24../29: " + cidr);
        }
        try {
            byte[] first = InetAddress.getByName(parts[0]).getAddress();
            if (first.length != 4) {
                throw new IllegalArgumentException("not IPv4: " + cidr);
            }
            byte[] last = first.clone();
            int hostBits = 32 - prefix;
            last[3] = (byte) (last[3] | ((1 << hostBits) - 1));
            AddressBlocklist blocklist = new AddressBlocklist(List.of());
            for (byte[] address : new byte[][] {first, last}) {
                if (blocklist.isBlocked(InetAddress.getByAddress(address))) {
                    throw new IllegalArgumentException("subnet is inside a range blocked by SPEC 5.3: " + cidr);
                }
            }
        } catch (UnknownHostException e) {
            throw new IllegalArgumentException("not an address: " + cidr, e);
        }
    }
}
