package de.alexanderlindhorst.imapmcp.core.search;

import jakarta.mail.Flags;
import jakarta.mail.Message;
import jakarta.mail.MessagingException;
import jakarta.mail.search.SearchTerm;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
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
 * The IMAP side of one search_messages page, on an open READ_ONLY folder. Per page exactly three commands,
 * independent of the page size: SEARCH, one FETCH (UID) over all matches, one FETCH of the page's summary items
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
    public record Page(int total, boolean hasMore, List<Row> rows) {
    }

    private record Hit(long uid, int seq) {
    }

    private PageFetcher() {
    }

    /** @param term null matches all messages; @param beforeUid exclusive keyset bound, 0 for none */
    public static Page page(IMAPFolder folder, SearchTerm term, long beforeUid, int pageSize)
            throws MessagingException {
        int[] seqs = matches(folder, term);
        if (seqs.length == 0) {
            return new Page(0, false, List.of());
        }
        List<Hit> eligible = new ArrayList<>();
        for (Hit h : hits(folder, seqs)) {
            if (beforeUid == 0 || h.uid() < beforeUid) {
                eligible.add(h);
            }
        }
        eligible.sort(Comparator.comparingLong(Hit::uid).reversed());
        List<Hit> page = eligible.subList(0, Math.min(pageSize, eligible.size()));
        boolean hasMore = eligible.size() > page.size();
        return new Page(seqs.length, hasMore, page.isEmpty() ? List.of() : rows(folder, page));
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
        Response[] responses = fetch(folder, seqs, "UID");
        List<Hit> hits = new ArrayList<>();
        for (Response r : responses) {
            // getItem hands out each item once, so it is read exactly once per class
            if (r instanceof FetchResponse f) {
                UID uid = f.getItem(UID.class);
                if (uid != null) {
                    hits.add(new Hit(uid.uid, f.getNumber()));
                }
            }
        }
        return hits;
    }

    private static List<Row> rows(IMAPFolder folder, List<Hit> page) throws MessagingException {
        int[] seqs = page.stream().mapToInt(Hit::seq).sorted().toArray();
        Set<Long> wanted = new HashSet<>();
        page.forEach(h -> wanted.add(h.uid()));
        List<Row> rows = new ArrayList<>();
        for (Response r : fetch(folder, seqs, SUMMARY_ITEMS)) {
            if (!(r instanceof FetchResponse f)) {
                continue;
            }
            UID id = f.getItem(UID.class);
            if (id == null || !wanted.remove(id.uid)) {
                continue; // not a message of this page (shifted by a concurrent expunge) or a duplicate
            }
            INTERNALDATE received = f.getItem(INTERNALDATE.class);
            RFC822SIZE size = f.getItem(RFC822SIZE.class);
            FLAGS flags = f.getItem(FLAGS.class);
            rows.add(new Row(id.uid, f.getItem(ENVELOPE.class), flags == null ? new Flags() : flags,
                    received == null ? null : received.getDate(), size == null ? 0 : size.size,
                    f.getItem(BODYSTRUCTURE.class), dateHeader(f.getItem(BODY.class))));
        }
        rows.sort(Comparator.comparingLong(Row::uid).reversed());
        return rows;
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
