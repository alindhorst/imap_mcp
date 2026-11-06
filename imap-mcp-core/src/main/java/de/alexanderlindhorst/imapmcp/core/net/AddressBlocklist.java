package de.alexanderlindhorst.imapmcp.core.net;

import java.net.InetAddress;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.stream.Stream;

/**
 * The block-range table of SPEC 5.3 plus operator-supplied {@code ssrf.extraBlockedCidrs}. IPv4-mapped IPv6 is
 * checked as the embedded IPv4 address. NAT64 ({@code 64:ff9b::/96}, {@code 64:ff9b:1::/48}) is blocked as a whole:
 * the embedded IPv4 address is not inspected. {@code ::/96} (this host, loopback and the deprecated IPv4-compatible
 * form) is blocked as a superset of the SPEC's {@code ::/128} and {@code ::1/128}.
 */
public final class AddressBlocklist {

    private static final List<Cidr> TABLE = Stream.of(
            "0.0.0.0/8", "10.0.0.0/8", "100.64.0.0/10", "127.0.0.0/8", "169.254.0.0/16", "172.16.0.0/12",
            "192.0.0.0/24", "192.0.2.0/24", "192.88.99.0/24", "192.168.0.0/16", "198.18.0.0/15",
            "198.51.100.0/24", "203.0.113.0/24", "224.0.0.0/4", "240.0.0.0/4",
            "::/96", "64:ff9b::/96", "64:ff9b:1::/48", "100::/64", "2001::/23", "2001:db8::/32", "2002::/16",
            "fc00::/7", "fe80::/10", "fec0::/10", "ff00::/8").map(Cidr::parse).toList();

    private final List<Cidr> ranges;

    public AddressBlocklist(List<Cidr> extra) {
        List<Cidr> all = new ArrayList<>(TABLE);
        all.addAll(Objects.requireNonNull(extra, "extra"));
        this.ranges = List.copyOf(all);
    }

    public boolean isBlocked(InetAddress address) {
        Objects.requireNonNull(address, "address");
        for (Cidr c : ranges) {
            if (c.contains(address)) {
                return true;
            }
        }
        return false;
    }
}
