package de.alexanderlindhorst.imapmcp.core.net;

import java.net.InetAddress;
import java.util.List;

/** The SPEC 5.3 block-range table plus operator-supplied extra ranges. */
public final class AddressBlocklist {

    public AddressBlocklist(List<Cidr> extra) {
        throw new UnsupportedOperationException();
    }

    public boolean isBlocked(InetAddress address) {
        throw new UnsupportedOperationException();
    }
}
