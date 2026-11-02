package de.alexanderlindhorst.imapmcp.core.search;

import jakarta.mail.Flags;
import jakarta.mail.Message;
import jakarta.mail.MessagingException;
import jakarta.mail.search.SearchTerm;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.Date;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.eclipse.angus.mail.iap.ByteArray;
import org.eclipse.angus.mail.iap.Response;
import org.eclipse.angus.mail.imap.IMAPFolder;
import org.eclipse.angus.mail.imap.protocol.BODY;
import org.eclipse.angus.mail.imap.protocol.BODYSTRUCTURE;
import org.eclipse.angus.mail.imap.protocol.ENVELOPE;
import org.eclipse.angus.mail.imap.protocol.FLAGS;
import org.eclipse.angus.mail.imap.protocol.FetchResponse;
import org.eclipse.angus.mail.imap.protocol.INTERNALDATE;
import org.eclipse.angus.mail.imap.protocol.MessageSet;
import org.eclipse.angus.mail.imap.protocol.RFC822SIZE;
import org.eclipse.angus.mail.imap.protocol.UID;

/**
 * The IMAP side of one search_messages page, on an open READ_ONLY folder. Commands per page, independent of the
 * page size: a SEARCH (only with criteria), on cursor pages one {@code UID FETCH <lastUid> (UID)} boundary
 * command, and one FETCH of the page's summary items
 * (UID FLAGS INTERNALDATE RFC822.SIZE ENVELOPE BODYSTRUCTURE and BODY.PEEK[HEADER.FIELDS (Date)], which is the
 * only way to get the Date header's original offset; no body content, nothing is marked \Seen). The matches are
 * ordered by UID descending and cut by the keyset ({@code uid < beforeUid}) here. Angus can only address messages
 * by sequence number on the wire; the numbers stay inside this class (the page is re-identified by the UID in
 * every response, so a concurrent expunge cannot attach a wrong summary to a UID) and no {@code Message} object
 * is kept. Fetched values are untrusted and never logged.
 */
public final class PageFetcher {

    /** Without outer parentheses: IMAPProtocol.fetch adds them itself. */
    static final String SUMMARY_ITEMS = "UID FLAGS INTERNALDATE RFC822.SIZE ENVELOPE BODYSTRUCTURE "
            + "BODY.PEEK[HEADER.FIELDS (Date)]";

    /** Raw fetched items of one message; {@code dateHeader} is the header block text or null. */
    public record Row(long uid, ENVELOPE envelope, Flags flags, Date received, long size, BODYSTRUCTURE structure,
            String dateHeader) {
    }

    /** {@code total} counts all matches, {@code hasMore} whether eligible matches remain after {@code rows}. */
    public record Page(int total, boolean hasMore, List<Row> rows, boolean tooManyCandidates) {
        Page(int total, boolean hasMore, List<Row> rows) {
            this(total, hasMore, rows, false);
        }
    }

    record Hit(long uid, int seq, Date received) {
    }

    private PageFetcher() {
    }

    /** @param term null matches all messages; @param beforeUid exclusive keyset bound, 0 for none */
    public static Page page(IMAPFolder folder, SearchTerm term, long beforeUid, int pageSize)
            throws MessagingException {
        return page(folder, term, beforeUid, pageSize, null, Integer.MAX_VALUE);
    }

    /**
     * Without {@code since} the UID data moved per page is bounded by the page, not the folder. EXISTS is read
     * first, then the boundary {@code b} (messages with a UID below {@code beforeUid}; UIDs ascend strictly with
     * sequence numbers, RFC 3501 2.3.1.1) from one raw {@code UID FETCH <beforeUid> (UID)}. Without criteria no
     * SEARCH is issued and the page is the seqs just below {@code b}; with criteria the SEARCH result (seqs only)
     * is cut at {@code b} and the highest ones form the page. A concurrent expunge can make {@code b} too high
     * (never too low); rows at or above {@code beforeUid} are then dropped and the deficit is fetched from the next
     * lower seqs, so a short page never ends paging by itself.
     * <p>
     * With {@code since} the SEARCH term already carries the coarse day bound; the candidate FETCH then also asks
     * for INTERNALDATE (same command, so no extra round trip) and everything received before {@code since} is
     * dropped, which makes {@code total} exact. If the SEARCH returned more than {@code maxCandidates} hits the
     * page is empty with {@code tooManyCandidates} set and nothing else is fetched.
     */
    public static Page page(IMAPFolder folder, SearchTerm term, long beforeUid, int pageSize, Instant since,
            int maxCandidates) throws MessagingException {
        if (since != null) {
            return sincePage(folder, term, beforeUid, pageSize, since, maxCandidates);
        }
        int exists = folder.getMessageCount(); // before any other command: no NOOP may slip in between
        if (exists == 0) {
            return new Page(0, false, List.of());
        }
        int boundary = beforeUid == 0 ? exists : boundary(folder, beforeUid, exists);
        int total;
        Candidates candidates;
        if (term == null) {
            total = exists;
            candidates = Candidates.range(boundary);
        } else {
            int[] seqs = matches(folder, term);
            total = seqs.length;
            candidates = Candidates.upTo(seqs, boundary);
        }
        int need = Math.min(pageSize, candidates.size());
        List<Row> rows = new ArrayList<>();
        Set<Long> seen = new HashSet<>();
        while (rows.size() < need && candidates.hasMore()) {
            for (Row r : rows(folder, candidates.take(need - rows.size()))) {
                if ((beforeUid == 0 || r.uid() < beforeUid) && seen.add(r.uid())) {
                    rows.add(r);
                }
            }
        }
        rows.sort(Comparator.comparingLong(Row::uid).reversed());
        return new Page(total, candidates.hasMore(), rows);
    }

    private static Page sincePage(IMAPFolder folder, SearchTerm term, long beforeUid, int pageSize, Instant since,
            int maxCandidates) throws MessagingException {
        int[] seqs = matches(folder, term);
        if (seqs.length == 0) {
            return new Page(0, false, List.of());
        }
        if (seqs.length > maxCandidates) {
            return new Page(0, false, List.of(), true);
        }
        List<Hit> matched = new ArrayList<>();
        for (Hit h : hits(folder, seqs)) {
            // a message without a readable INTERNALDATE cannot be shown to be at or after since
            if (h.received() != null && !h.received().toInstant().isBefore(since)) {
                matched.add(h);
            }
        }
        List<Hit> eligible = new ArrayList<>();
        for (Hit h : matched) {
            if (beforeUid == 0 || h.uid() < beforeUid) {
                eligible.add(h);
            }
        }
        eligible.sort(Comparator.comparingLong(Hit::uid).reversed());
        List<Hit> page = eligible.subList(0, Math.min(pageSize, eligible.size()));
        boolean hasMore = eligible.size() > page.size();
        List<Row> rows = List.of();
        if (!page.isEmpty()) {
            Set<Long> wanted = new HashSet<>();
            page.forEach(h -> wanted.add(h.uid()));
            rows = new ArrayList<>();
            for (Row r : rows(folder, page.stream().mapToInt(Hit::seq).toArray())) {
                if (wanted.remove(r.uid())) { // not a message of this page (shifted by an expunge) or a duplicate
                    rows.add(r);
                }
            }
        }
        return new Page(matched.size(), hasMore, rows);
    }

    /** Descending source of candidate sequence numbers, consumed page by page. */
    private static final class Candidates {
        private final int[] seqs; // ascending, null for a plain range 1..hi
        private int hi; // range: highest seq not yet taken; array: number of entries not yet taken

        private Candidates(int[] seqs, int hi) {
            this.seqs = seqs;
            this.hi = hi;
        }

        static Candidates range(int boundary) {
            return new Candidates(null, Math.max(0, boundary));
        }

        static Candidates upTo(int[] ascending, int boundary) {
            int[] sorted = ascending.clone();
            java.util.Arrays.sort(sorted);
            int n = 0;
            while (n < sorted.length && sorted[n] <= boundary) {
                n++;
            }
            return new Candidates(sorted, n);
        }

        int size() {
            return hi;
        }

        boolean hasMore() {
            return hi > 0;
        }

        /** The next {@code n} highest candidates, ascending. */
        int[] take(int n) {
            int count = Math.min(n, hi);
            int[] out = new int[count];
            for (int i = 0; i < count; i++) {
                int idx = hi - count + i;
                out[i] = seqs == null ? idx + 1 : seqs[idx];
            }
            hi -= count;
            return out;
        }
    }

    /**
     * Number of messages with a UID below {@code beforeUid}. One raw {@code UID FETCH <uid> (UID)}; if that message
     * was expunged meanwhile, {@code UID FETCH <uid>:* (UID)} gives the first message above it. Angus has no API
     * for this; the sequence numbers stay inside this class.
     */
    private static int boundary(IMAPFolder folder, long beforeUid, int exists) throws MessagingException {
        for (FetchResponse f : uidFetch(folder, beforeUid + " (UID)")) {
            if (f.getItem(UID.class) instanceof UID u && u.uid == beforeUid) {
                return f.getNumber() - 1;
            }
        }
        int min = Integer.MAX_VALUE;
        for (FetchResponse f : uidFetch(folder, beforeUid + ":* (UID)")) {
            // `*` also yields the last message when its UID is below beforeUid: filter by UID
            if (f.getItem(UID.class) instanceof UID u && u.uid >= beforeUid) {
                min = Math.min(min, f.getNumber());
            }
        }
        return min == Integer.MAX_VALUE ? exists : min - 1;
    }

    private static List<FetchResponse> uidFetch(IMAPFolder folder, String setAndItems) throws MessagingException {
        Response[] responses = (Response[]) folder.doCommand(p -> {
            Response[] r = p.command("UID FETCH " + setAndItems, null);
            p.notifyResponseHandlers(r);
            p.handleResult(r[r.length - 1]);
            return r;
        });
        List<FetchResponse> out = new ArrayList<>();
        for (Response r : responses) {
            if (r instanceof FetchResponse f) {
                out.add(f);
            }
        }
        return out;
    }

    private static int[] matches(IMAPFolder folder, SearchTerm term) throws MessagingException {
        if (term == null) {
            int[] all = new int[folder.getMessageCount()];
            for (int i = 0; i < all.length; i++) {
                all[i] = i + 1;
            }
            return all;
        }
        Message[] found = folder.search(term);
        int[] seqs = new int[found.length];
        for (int i = 0; i < found.length; i++) {
            seqs[i] = found[i].getMessageNumber();
        }
        return seqs;
    }

    private static List<Hit> hits(IMAPFolder folder, int[] seqs) throws MessagingException {
        return hits(fetch(folder, seqs, "UID INTERNALDATE"), seqs);
    }

    /**
     * Package-private for tests with fabricated responses. Only responses for the requested seqs count (unsolicited
     * FETCH data for other messages may arrive during any command, RFC 3501 7.4.2); the items of one seq may be
     * split over several responses and are merged.
     */
    static List<Hit> hits(Response[] responses, int[] seqs) {
        List<Hit> hits = new ArrayList<>();
        for (Merged m : merge(responses, seqs)) {
            if (m.uid != null) {
                hits.add(new Hit(m.uid.uid, m.seq, m.received == null ? null : m.received.getDate()));
            }
        }
        return hits;
    }

    /** The summary rows of the given seqs, identified by the UID in each response, newest first. */
    private static List<Row> rows(IMAPFolder folder, int[] seqs) throws MessagingException {
        int[] sorted = seqs.clone();
        java.util.Arrays.sort(sorted);
        return rows(fetch(folder, sorted, SUMMARY_ITEMS), sorted);
    }

    /**
     * Package-private for tests with fabricated responses. A row needs the UID and the ENVELOPE of a requested seq,
     * merged over all its responses; anything else (unsolicited, FLAGS-only, partial) creates no row.
     */
    static List<Row> rows(Response[] responses, int[] seqs) {
        List<Row> rows = new ArrayList<>();
        for (Merged m : merge(responses, seqs)) {
            if (m.uid == null || m.envelope == null) {
                continue;
            }
            rows.add(new Row(m.uid.uid, m.envelope, m.flags == null ? new Flags() : m.flags,
                    m.received == null ? null : m.received.getDate(), m.size == null ? 0 : m.size.size,
                    m.structure, dateHeader(m.body)));
        }
        rows.sort(Comparator.comparingLong(Row::uid).reversed());
        return rows;
    }

    /** The items of one sequence number over all its FETCH responses; the first of each kind wins, FLAGS the last. */
    private static final class Merged {
        final int seq;
        UID uid;
        FLAGS flags;
        INTERNALDATE received;
        RFC822SIZE size;
        ENVELOPE envelope;
        BODYSTRUCTURE structure;
        BODY body;

        Merged(int seq) {
            this.seq = seq;
        }
    }

    private static Collection<Merged> merge(Response[] responses, int[] seqs) {
        Set<Integer> requested = new HashSet<>();
        for (int s : seqs) {
            requested.add(s);
        }
        Map<Integer, Merged> bySeq = new LinkedHashMap<>();
        for (Response r : responses) {
            if (!(r instanceof FetchResponse f) || !requested.contains(f.getNumber())) {
                continue;
            }
            Merged m = bySeq.computeIfAbsent(f.getNumber(), Merged::new);
            // getItem hands out each item once, so it is read exactly once per class
            UID uid = f.getItem(UID.class);
            m.uid = m.uid == null ? uid : m.uid;
            FLAGS flags = f.getItem(FLAGS.class);
            m.flags = flags == null ? m.flags : flags;
            INTERNALDATE received = f.getItem(INTERNALDATE.class);
            m.received = m.received == null ? received : m.received;
            RFC822SIZE size = f.getItem(RFC822SIZE.class);
            m.size = m.size == null ? size : m.size;
            ENVELOPE envelope = f.getItem(ENVELOPE.class);
            m.envelope = m.envelope == null ? envelope : m.envelope;
            BODYSTRUCTURE structure = f.getItem(BODYSTRUCTURE.class);
            m.structure = m.structure == null ? structure : m.structure;
            BODY body = f.getItem(BODY.class);
            m.body = m.body == null ? body : m.body;
        }
        return bySeq.values();
    }

    private static String dateHeader(BODY body) {
        ByteArray bytes = body == null ? null : body.getByteArray();
        return bytes == null ? null : new String(bytes.getNewBytes(), StandardCharsets.ISO_8859_1);
    }

    private static Response[] fetch(IMAPFolder folder, int[] seqs, String items) throws MessagingException {
        MessageSet[] sets = MessageSet.createMessageSets(seqs);
        return (Response[]) folder.doCommand(p -> p.fetch(sets, items));
    }
}
