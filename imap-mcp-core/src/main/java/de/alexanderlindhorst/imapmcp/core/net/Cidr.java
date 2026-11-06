package de.alexanderlindhorst.imapmcp.core.net;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.Arrays;
import java.util.regex.Pattern;

/**
 * An IPv4 or IPv6 prefix. An IPv4-mapped IPv6 address ({@code ::ffff:a.b.c.d}) is compared as its embedded IPv4
 * address, whatever shape the JDK gave it.
 */
public final class Cidr {

    private static final Pattern DOTTED_QUAD =
            Pattern.compile("(25[0-5]|2[0-4]\\d|1\\d\\d|[1-9]?\\d)(\\.(25[0-5]|2[0-4]\\d|1\\d\\d|[1-9]?\\d)){3}");
    private static final Pattern V6_CHARS = Pattern.compile("[0-9a-fA-F:.]+");

    private final byte[] network;
    private final int prefix;

    private Cidr(byte[] network, int prefix) {
        this.network = network;
        this.prefix = prefix;
    }

    /** Parses {@code a.b.c.d/n} or {@code <ipv6>/n}. Literals only; never resolves a name. */
    public static Cidr parse(String text) {
        if (text == null) {
            throw new IllegalArgumentException("invalid CIDR");
        }
        int slash = text.indexOf('/');
        if (slash <= 0 || slash != text.lastIndexOf('/') || slash == text.length() - 1) {
            throw new IllegalArgumentException("invalid CIDR");
        }
        String addr = text.substring(0, slash);
        String len = text.substring(slash + 1);
        if (len.length() > 3 || !len.chars().allMatch(c -> c >= '0' && c <= '9')) {
            throw new IllegalArgumentException("invalid CIDR");
        }
        int prefix = Integer.parseInt(len);
        boolean v6 = addr.indexOf(':') >= 0;
        if (v6 ? !V6_CHARS.matcher(addr).matches() : !DOTTED_QUAD.matcher(addr).matches()) {
            throw new IllegalArgumentException("invalid CIDR");
        }
        byte[] bytes;
        try {
            bytes = InetAddress.getByName(addr).getAddress(); // a literal: no DNS lookup
        } catch (UnknownHostException e) {
            throw new IllegalArgumentException("invalid CIDR", e);
        }
        if (v6 && bytes.length == 4) { // IPv4-mapped written with a 128-bit prefix length
            if (prefix < 96) {
                throw new IllegalArgumentException("invalid CIDR");
            }
            prefix -= 96;
        }
        if (prefix > bytes.length * 8) {
            throw new IllegalArgumentException("invalid CIDR");
        }
        return new Cidr(mask(bytes, prefix), prefix);
    }

    /** The address bytes with IPv4-mapped IPv6 reduced to the embedded IPv4 address. */
    static byte[] normalize(InetAddress address) {
        byte[] b = address.getAddress();
        if (b.length == 16) {
            boolean mapped = b[10] == (byte) 0xff && b[11] == (byte) 0xff;
            for (int i = 0; i < 10 && mapped; i++) {
                mapped = b[i] == 0;
            }
            if (mapped) {
                return Arrays.copyOfRange(b, 12, 16);
            }
        }
        return b;
    }

    public boolean contains(InetAddress address) {
        byte[] b = normalize(address);
        return b.length == network.length && Arrays.equals(mask(b, prefix), network);
    }

    private static byte[] mask(byte[] in, int bits) {
        byte[] out = in.clone();
        for (int i = 0; i < out.length; i++) {
            int keep = Math.max(0, Math.min(8, bits - i * 8));
            out[i] = (byte) (out[i] & (0xff << (8 - keep)));
        }
        return out;
    }
}
