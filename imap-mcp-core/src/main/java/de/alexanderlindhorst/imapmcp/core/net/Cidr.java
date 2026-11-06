package de.alexanderlindhorst.imapmcp.core.net;

import java.net.InetAddress;

/** An IPv4 or IPv6 prefix. IPv4-mapped IPv6 addresses are compared as their embedded IPv4 address. */
public final class Cidr {

    public static Cidr parse(String text) {
        throw new UnsupportedOperationException();
    }

    public boolean contains(InetAddress address) {
        throw new UnsupportedOperationException();
    }
}
