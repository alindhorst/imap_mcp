package de.alexanderlindhorst.imapmcp.core.tools.write;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.eclipse.angus.mail.iap.Response;

/**
 * Parses {@code [COPYUID <uidvalidity> <src-set> <dst-set>]} (RFC 4315) from the untagged OK of a UID MOVE
 * (RFC 6851) or the tagged OK of a UID COPY (SPEC 5.4). Source and destination UIDs are paired by position in the
 * response sets, which is the server's order, not the request order. A malformed code (unequal set sizes,
 * non-numeric, zero, {@code *}, oversized) counts as absent. Pure: no I/O, no logging.
 */
public final class CopyUidParser {

    /** Upper bound for an expanded set; a larger set is treated as malformed. */
    static final int MAX_EXPANDED = 100_000;

    private static final long MAX_UINT32 = 4_294_967_295L;
    private static final Pattern CODE = Pattern.compile(
            "\\A\\[COPYUID ([0-9]{1,10}) ([0-9:,]{1,2000}) ([0-9:,]{1,2000})\\]", Pattern.CASE_INSENSITIVE);

    /** Paired UIDs in response order; both lists have the same size. */
    public record CopyUid(long uidValidity, List<Long> sourceUids, List<Long> destinationUids) {

        public CopyUid {
            sourceUids = List.copyOf(sourceUids);
            destinationUids = List.copyOf(destinationUids);
        }

        /** The destination UID paired with a source UID, empty if the source UID is not in the source set. */
        public OptionalLong destinationOf(long sourceUid) {
            int i = sourceUids.indexOf(sourceUid);
            return i < 0 ? OptionalLong.empty() : OptionalLong.of(destinationUids.get(i));
        }
    }

    private CopyUidParser() {
    }

    /** The first well-formed COPYUID in an OK response; empty if there is none. */
    public static Optional<CopyUid> parse(Response[] responses) {
        if (responses == null) {
            return Optional.empty();
        }
        for (Response r : responses) {
            Optional<CopyUid> c = parse(r);
            if (c.isPresent()) {
                return c;
            }
        }
        return Optional.empty();
    }

    /**
     * All well-formed COPYUID codes of the OK responses of one command, merged in response order (a server may
     * report a UID MOVE in chunks). Empty if there is none, if the UIDVALIDITY values differ, or if the merged
     * set would exceed {@link #MAX_EXPANDED}.
     */
    public static Optional<CopyUid> parseAll(Response[] responses) {
        if (responses == null) {
            return Optional.empty();
        }
        long validity = -1;
        List<Long> src = new ArrayList<>();
        List<Long> dst = new ArrayList<>();
        for (Response r : responses) {
            Optional<CopyUid> c = parse(r);
            if (c.isEmpty()) {
                continue;
            }
            if (validity >= 0 && validity != c.get().uidValidity()) {
                return Optional.empty();
            }
            validity = c.get().uidValidity();
            src.addAll(c.get().sourceUids());
            dst.addAll(c.get().destinationUids());
            if (src.size() > MAX_EXPANDED) {
                return Optional.empty();
            }
        }
        return validity < 0 ? Optional.empty() : Optional.of(new CopyUid(validity, src, dst));
    }

    public static Optional<CopyUid> parse(Response response) {
        if (response == null || !response.isOK()) {
            return Optional.empty();
        }
        String rest = response.getRest();
        if (rest == null) {
            return Optional.empty();
        }
        Matcher m = CODE.matcher(rest.stripLeading());
        if (!m.find()) {
            return Optional.empty();
        }
        long validity = Long.parseLong(m.group(1));
        if (validity < 1 || validity > MAX_UINT32) {
            return Optional.empty();
        }
        List<Long> src = expand(m.group(2));
        List<Long> dst = expand(m.group(3));
        if (src == null || dst == null || src.size() != dst.size()) {
            return Optional.empty();
        }
        return Optional.of(new CopyUid(validity, src, dst));
    }

    /** Expands {@code 3:5,9}; null if malformed or larger than {@link #MAX_EXPANDED}. */
    private static List<Long> expand(String set) {
        List<Long> out = new ArrayList<>();
        for (String part : set.split(",", -1)) {
            int colon = part.indexOf(':');
            long from;
            long to;
            try {
                if (colon < 0) {
                    from = to = uid(part);
                } else {
                    from = uid(part.substring(0, colon));
                    to = uid(part.substring(colon + 1));
                }
            } catch (NumberFormatException e) {
                return null;
            }
            long step = from <= to ? 1 : -1;
            if (Math.abs(to - from) + out.size() >= MAX_EXPANDED) {
                return null;
            }
            for (long u = from; ; u += step) {
                out.add(u);
                if (u == to) {
                    break;
                }
            }
        }
        return out;
    }

    private static long uid(String s) {
        if (s.isEmpty() || s.length() > 10) {
            throw new NumberFormatException();
        }
        long v = Long.parseLong(s);
        if (v < 1 || v > MAX_UINT32) {
            throw new NumberFormatException();
        }
        return v;
    }
}
