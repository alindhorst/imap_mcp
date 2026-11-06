package de.alexanderlindhorst.imapmcp.core.net;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class AddressBlocklistTest {

    private final AddressBlocklist list = new AddressBlocklist(List.of());

    /** Literal only; never triggers a DNS lookup. */
    static InetAddress ip(String literal) {
        try {
            return InetAddress.getByName(literal);
        } catch (UnknownHostException e) {
            throw new IllegalArgumentException(e);
        }
    }

    /** A 16-byte address, even where the JDK would collapse it (IPv4-mapped). */
    static InetAddress raw16(int... bytes) {
        byte[] b = new byte[16];
        for (int i = 0; i < bytes.length; i++) {
            b[i] = (byte) bytes[i];
        }
        try {
            return InetAddress.getByAddress(b);
        } catch (UnknownHostException e) {
            throw new IllegalArgumentException(e);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "0.0.0.0", "0.255.255.255", "10.0.0.1", "10.255.255.255", "100.64.0.1", "100.127.255.255",
            "127.0.0.1", "127.255.255.254", "169.254.169.254", "172.16.0.1", "172.31.255.255", "192.0.0.8",
            "192.0.2.1", "192.88.99.1", "192.168.1.1", "198.18.0.1", "198.19.255.255", "198.51.100.7",
            "203.0.113.9", "224.0.0.1", "239.255.255.255", "240.0.0.1", "255.255.255.255",
            "::", "::1", "64:ff9b::808:808", "64:ff9b:1::1", "64:ff9b:1:ffff::1", "100::1", "2001::1",
            "2001:1ff:ffff::1", "2001:db8::1", "2002::1", "2002:7f00:1::", "fc00::1", "fdff::1", "fe80::1",
            "febf::1", "fec0::1", "feff::1", "ff02::1", "ff00::"})
    void blockedAddresses(String literal) {
        assertThat(list.isBlocked(ip(literal))).as(literal).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "1.1.1.1", "8.8.8.8", "9.255.255.255", "11.0.0.0", "100.63.255.255", "100.128.0.0", "126.255.255.255",
            "128.0.0.1", "169.253.255.255", "169.255.0.0", "172.15.255.255", "172.32.0.0", "192.0.1.1",
            "192.0.3.1", "192.167.255.255", "192.169.0.0", "198.17.255.255", "198.20.0.0", "198.51.101.1",
            "203.0.112.255", "203.0.114.0", "223.255.255.255", "93.184.216.34",
            "2001:4860:4860::8888", "2606:4700:4700::1111", "2a00:1450:4001::200e", "2001:200::1", "2003::1",
            "100:0:0:1::1", "fbff::1", "fe00::1"})
    void publicAddressesAreAllowed(String literal) {
        assertThat(list.isBlocked(ip(literal))).as(literal).isFalse();
    }

    @Test
    void ipv4MappedIsCheckedAsTheEmbeddedIpv4() {
        // ::ffff:10.0.0.1 as 16 bytes (the JDK collapses it to an Inet4Address; both shapes must be refused)
        assertThat(list.isBlocked(raw16(0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0xff, 0xff, 10, 0, 0, 1))).isTrue();
        assertThat(list.isBlocked(raw16(0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0xff, 0xff, 127, 0, 0, 1))).isTrue();
        assertThat(list.isBlocked(raw16(0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0xff, 0xff, 169, 254, 169, 254))).isTrue();
        assertThat(list.isBlocked(ip("::ffff:192.168.0.1"))).isTrue();
        // a public IPv4 inside the mapped form stays allowed: the embedded address decides
        assertThat(list.isBlocked(raw16(0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0xff, 0xff, 8, 8, 8, 8))).isFalse();
        assertThat(list.isBlocked(ip("::ffff:8.8.8.8"))).isFalse();
    }

    @Test
    void nat64IsBlockedEvenForPublicEmbeddedIpv4() {
        assertThat(list.isBlocked(ip("64:ff9b::808:808"))).isTrue();
        assertThat(list.isBlocked(ip("64:ff9b::a00:1"))).isTrue();
        assertThat(list.isBlocked(ip("64:ff9b:1:1::1"))).isTrue();
    }

    @Test
    void ipv4CompatibleFormIsBlocked() {
        assertThat(list.isBlocked(ip("::7f00:1"))).isTrue();
    }

    @Test
    void extraCidrsAreAdded() {
        AddressBlocklist extra = new AddressBlocklist(List.of(Cidr.parse("93.184.216.0/24"),
                Cidr.parse("2606:4700::/32")));
        assertThat(extra.isBlocked(ip("93.184.216.34"))).isTrue();
        assertThat(extra.isBlocked(ip("93.184.217.34"))).isFalse();
        assertThat(extra.isBlocked(ip("2606:4700:4700::1111"))).isTrue();
        assertThat(extra.isBlocked(ip("2606:4701::1"))).isFalse();
        assertThat(extra.isBlocked(ip("10.0.0.1"))).isTrue();
    }

    @Test
    void cidrParsingRejectsGarbage() {
        for (String bad : List.of("", "10.0.0.0", "10.0.0.0/33", "10.0.0.0/-1", "example.com/8", "10.0.0/8",
                "::/129", "1.2.3.4/x", "::ffff:10.0.0.0/64")) {
            assertThatThrownBy(() -> Cidr.parse(bad)).as(bad).isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Test
    void cidrAcceptsMappedFormWithV6PrefixLength() {
        Cidr c = Cidr.parse("::ffff:93.184.216.0/120");
        assertThat(c.contains(ip("93.184.216.1"))).isTrue();
        assertThat(c.contains(ip("93.184.217.1"))).isFalse();
    }
}
