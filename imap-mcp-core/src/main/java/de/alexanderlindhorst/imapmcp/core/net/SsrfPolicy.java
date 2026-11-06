package de.alexanderlindhorst.imapmcp.core.net;

import de.alexanderlindhorst.imapmcp.core.account.MailAccount.Security;
import de.alexanderlindhorst.imapmcp.core.net.HostRejectedException.Reason;
import java.net.IDN;
import java.net.InetAddress;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * SSRF policy of {@code http-public} (SPEC 5.3): host syntax and allowlist, port and TLS rule, blocked addresses.
 * Used at link time and, through {@link PinnedSocketFactory}, on every TCP connection. Rejections carry a
 * {@link Reason} only, never the host.
 */
public final class SsrfPolicy {

    private static final Logger LOG = LoggerFactory.getLogger(SsrfPolicy.class);

    static final int IMAPS_PORT = 993;
    static final int IMAP_PORT = 143;
    private static final int MAX_HOST = 253;
    private static final int MAX_RAW = 1024;
    private static final Pattern LABEL = Pattern.compile("[a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?");
    private static final Pattern V6_LITERAL = Pattern.compile("\\[?[0-9a-fA-F:.]+(%[0-9A-Za-z._~-]+)?]?");
    private static final Pattern HEX_LABEL = Pattern.compile("0[xX][0-9a-fA-F]*");

    private final List<String> exact;
    private final List<String> suffixes;
    private final boolean enforceAllowlist;
    private final AddressBlocklist blocklist;

    private SsrfPolicy(List<String> exact, List<String> suffixes, AddressBlocklist blocklist) {
        this.exact = exact;
        this.suffixes = suffixes;
        this.enforceAllowlist = !exact.isEmpty() || !suffixes.isEmpty();
        this.blocklist = blocklist;
    }

    /**
     * {@code allowedHosts} are exact names or {@code *.} suffix patterns (IDN allowed, stored as A-labels). An empty
     * list is refused unless {@code allowAnyPublicHost} (WARN); a non-empty list is always enforced.
     *
     * @throws IllegalArgumentException for an empty list without {@code allowAnyPublicHost}, a bad pattern or CIDR
     */
    public static SsrfPolicy of(List<String> allowedHosts, boolean allowAnyPublicHost, List<String> extraBlockedCidrs) {
        Objects.requireNonNull(allowedHosts, "allowedHosts");
        Objects.requireNonNull(extraBlockedCidrs, "extraBlockedCidrs");
        if (allowedHosts.isEmpty() && !allowAnyPublicHost) {
            throw new IllegalArgumentException("ssrf.allowedHosts is empty and ssrf.allowAnyPublicHost is not set");
        }
        List<String> exact = new ArrayList<>();
        List<String> suffixes = new ArrayList<>();
        for (String pattern : allowedHosts) {
            if (pattern != null && pattern.startsWith("*.")) {
                String suffix = validate(pattern.substring(2));
                if (suffix.indexOf('.') < 0) {
                    throw new IllegalArgumentException("ssrf.allowedHosts: suffix pattern too broad");
                }
                suffixes.add(suffix);
            } else {
                exact.add(validate(pattern));
            }
        }
        if (allowedHosts.isEmpty()) {
            LOG.warn("ssrf.allowAnyPublicHost is set: any public IMAP host may be linked");
        }
        List<Cidr> extra = extraBlockedCidrs.stream().map(Cidr::parse).toList();
        return new SsrfPolicy(List.copyOf(exact), List.copyOf(suffixes), new AddressBlocklist(extra));
    }

    /**
     * Normalizes a host name (lower-case A-label form) and applies the allowlist.
     *
     * @throws HostRejectedException for a malformed name, an IP literal or a name outside the allowlist
     */
    public String normalizeHost(String host) {
        String name = validate(host);
        if (enforceAllowlist && !exact.contains(name) && suffixes.stream().noneMatch(s -> name.endsWith("." + s))) {
            throw new HostRejectedException(Reason.NOT_ALLOWED);
        }
        return name;
    }

    /** {@link #normalizeHost} plus the port rule: 993 with implicit TLS or 143 with STARTTLS, nothing else. */
    public String checkEndpoint(String host, int port, Security security) {
        String name = normalizeHost(host);
        boolean ok = (port == IMAPS_PORT && security == Security.IMPLICIT_TLS)
                || (port == IMAP_PORT && security == Security.STARTTLS);
        if (!ok) {
            throw new HostRejectedException(Reason.PORT);
        }
        return name;
    }

    public boolean isBlocked(InetAddress address) {
        return blocklist.isBlocked(address);
    }

    /** Host syntax only: a DNS name of at least two labels, IDN as A-label, no IP literal in any notation. */
    private static String validate(String raw) {
        if (raw == null || raw.isEmpty() || raw.length() > MAX_RAW) {
            throw new HostRejectedException(Reason.INVALID_HOST);
        }
        if (raw.indexOf(':') >= 0 || raw.startsWith("[")) {
            throw new HostRejectedException(
                    V6_LITERAL.matcher(raw).matches() && raw.chars().filter(c -> c == ':').count() >= 2
                            ? Reason.IP_LITERAL : Reason.INVALID_HOST);
        }
        String ascii;
        try {
            ascii = IDN.toASCII(raw, IDN.USE_STD3_ASCII_RULES).toLowerCase(Locale.ROOT);
        } catch (IllegalArgumentException e) {
            throw new HostRejectedException(Reason.INVALID_HOST);
        }
        if (ascii.isEmpty() || ascii.length() > MAX_HOST + 1) {
            throw new HostRejectedException(Reason.INVALID_HOST);
        }
        // Resolvers accept 2130706433, 0x7f.1, 127.1: a numeric or 0x last label is never a real TLD.
        String bare = ascii.endsWith(".") ? ascii.substring(0, ascii.length() - 1) : ascii;
        String last = bare.substring(bare.lastIndexOf('.') + 1);
        if (!last.isEmpty() && (last.chars().allMatch(c -> c >= '0' && c <= '9') || HEX_LABEL.matcher(last).matches())) {
            throw new HostRejectedException(Reason.IP_LITERAL);
        }
        if (ascii.endsWith(".") || ascii.length() > MAX_HOST) {
            throw new HostRejectedException(Reason.INVALID_HOST);
        }
        String[] labels = ascii.split("\\.", -1);
        if (labels.length < 2) {
            throw new HostRejectedException(Reason.INVALID_HOST);
        }
        for (String label : labels) {
            if (!LABEL.matcher(label).matches()) {
                throw new HostRejectedException(Reason.INVALID_HOST);
            }
        }
        return ascii;
    }
}
