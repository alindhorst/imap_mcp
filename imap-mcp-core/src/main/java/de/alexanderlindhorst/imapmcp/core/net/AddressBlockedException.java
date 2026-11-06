package de.alexanderlindhorst.imapmcp.core.net;

import java.io.IOException;

/**
 * A connect was refused by the SSRF policy (SPEC 5.3): blocked address, host not allowed, IP literal or port. Mapped
 * to {@code UPSTREAM_ERROR} with {@code details.reason} {@code address_blocked}. The message never carries the host
 * or the address.
 */
public final class AddressBlockedException extends IOException {

    private static final long serialVersionUID = 1L;

    public AddressBlockedException() {
        super("address_blocked");
    }
}
