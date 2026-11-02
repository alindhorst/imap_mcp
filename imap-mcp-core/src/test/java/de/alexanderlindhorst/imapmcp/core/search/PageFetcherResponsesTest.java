package de.alexanderlindhorst.imapmcp.core.search;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import java.util.List;
import org.eclipse.angus.mail.iap.Response;
import org.eclipse.angus.mail.imap.protocol.FetchResponse;
import org.eclipse.angus.mail.imap.protocol.IMAPProtocol;
import org.eclipse.angus.mail.imap.protocol.IMAPResponse;
import org.junit.jupiter.api.Test;

/** Filtering and merging of FETCH responses with fabricated response lines (no server). */
class PageFetcherResponsesTest {

    private static final String ENVELOPE = "ENVELOPE (\"Mon, 1 Jan 2024 10:00:00 +0000\" \"Hello\" "
            + "((NIL NIL \"a\" \"example.org\")) ((NIL NIL \"a\" \"example.org\")) ((NIL NIL \"a\" \"example.org\")) "
            + "((NIL NIL \"b\" \"example.org\")) NIL NIL NIL \"<id@example.org>\")";
    private static final String STRUCTURE = "BODYSTRUCTURE (\"TEXT\" \"PLAIN\" (\"CHARSET\" \"UTF-8\") NIL NIL "
            + "\"7BIT\" 5 1)";

    private static Response[] responses(String... lines) throws Exception {
        Response[] out = new Response[lines.length];
        for (int i = 0; i < lines.length; i++) {
            out[i] = new FetchResponse(new IMAPResponse(lines[i]), null, mock(IMAPProtocol.class));
        }
        return out;
    }

    private static String full(int seq, long uid, String flags) {
        return "* " + seq + " FETCH (UID " + uid + " FLAGS (" + flags + ") INTERNALDATE \"01-Jan-2024 10:00:00 +0000\" "
                + "RFC822.SIZE 1234 " + ENVELOPE + " " + STRUCTURE + ")";
    }

    @Test
    void unsolicitedResponseForUnrequestedSeqIsIgnored() throws Exception {
        Response[] r = responses("* 9 FETCH (UID 900 FLAGS (\\Seen))", full(2, 20, "\\Recent"), full(3, 30, ""));

        List<PageFetcher.Row> rows = PageFetcher.rows(r, new int[] {2, 3});

        assertThat(rows).extracting(PageFetcher.Row::uid).containsExactly(30L, 20L);
    }

    @Test
    void flagsOnlyResponseBeforeTheFullOneDoesNotReplaceTheRow() throws Exception {
        Response[] r = responses("* 2 FETCH (UID 20 FLAGS (\\Seen))", full(2, 20, "\\Seen \\Flagged"));

        List<PageFetcher.Row> rows = PageFetcher.rows(r, new int[] {2});

        assertThat(rows).hasSize(1);
        PageFetcher.Row row = rows.get(0);
        assertThat(row.uid()).isEqualTo(20L);
        assertThat(row.envelope()).isNotNull();
        assertThat(row.size()).isEqualTo(1234);
        assertThat(row.structure()).isNotNull();
        assertThat(row.flags().contains(jakarta.mail.Flags.Flag.FLAGGED)).isTrue();
    }

    @Test
    void splitResponsesOfOneSeqMergeToOneCompleteRow() throws Exception {
        Response[] r = responses(
                "* 2 FETCH (UID 20 FLAGS (\\Seen) INTERNALDATE \"01-Jan-2024 10:00:00 +0000\" RFC822.SIZE 1234)",
                "* 2 FETCH (" + ENVELOPE + " " + STRUCTURE + ")");

        List<PageFetcher.Row> rows = PageFetcher.rows(r, new int[] {2});

        assertThat(rows).hasSize(1);
        PageFetcher.Row row = rows.get(0);
        assertThat(row.uid()).isEqualTo(20L);
        assertThat(row.envelope()).isNotNull();
        assertThat(row.structure()).isNotNull();
        assertThat(row.size()).isEqualTo(1234);
        assertThat(row.received()).isNotNull();
        assertThat(row.flags().contains(jakarta.mail.Flags.Flag.SEEN)).isTrue();
    }

    @Test
    void requestedSeqWithoutEnvelopeYieldsNoRow() throws Exception {
        Response[] r = responses("* 2 FETCH (UID 20 FLAGS (\\Seen))");

        assertThat(PageFetcher.rows(r, new int[] {2})).isEmpty();
    }

    @Test
    void hitsIgnoreUnrequestedSeqsAndMergeSplitResponses() throws Exception {
        Response[] r = responses("* 9 FETCH (UID 900 FLAGS (\\Seen))",
                "* 2 FETCH (UID 20)", "* 2 FETCH (INTERNALDATE \"01-Jan-2024 10:00:00 +0000\")");

        List<PageFetcher.Hit> hits = PageFetcher.hits(r, new int[] {2});

        assertThat(hits).hasSize(1);
        assertThat(hits.get(0).uid()).isEqualTo(20L);
        assertThat(hits.get(0).received()).isNotNull();
    }
}
