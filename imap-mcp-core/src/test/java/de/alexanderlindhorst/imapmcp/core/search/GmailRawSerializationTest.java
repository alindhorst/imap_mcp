package de.alexanderlindhorst.imapmcp.core.search;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.Properties;
import org.eclipse.angus.mail.gimap.protocol.GmailSearchSequence;
import org.eclipse.angus.mail.iap.Argument;
import org.eclipse.angus.mail.iap.Protocol;
import org.junit.jupiter.api.Test;

/**
 * AC2 of #29 without a server: the term from {@link SearchTermMapper} is run through Angus' own
 * {@link GmailSearchSequence} (what a gimaps connection uses) and the resulting {@link Argument} is written to an
 * in-memory {@link Protocol}; the bytes are what would go on the wire after the SEARCH command name.
 */
class GmailRawSerializationTest {

    private static final SearchCriteria E = SearchCriteria.empty();

    private static final class Sink extends Protocol {
        Sink(ByteArrayOutputStream out) throws java.io.IOException {
            super(new ByteArrayInputStream(new byte[0]), new PrintStream(out), new Properties(), false);
        }

        void flush() throws java.io.IOException {
            getOutputStream().flush();
        }
    }

    private static String wire(SearchCriteria c) throws Exception {
        Argument arg = new GmailSearchSequence(null).generateSequence(new SearchTermMapper().map(c), null);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        Sink p = new Sink(out);
        arg.write(p);
        p.flush();
        return out.toString(StandardCharsets.ISO_8859_1);
    }

    // Angus writes an IMAP astring: a bare atom when no special character occurs (equivalent on the wire to the
    // quoted form "has:attachment"), a quoted string as soon as one does.
    @Test
    void plainQueryIsAnAtomAndAQueryWithSpacesIsQuoted() throws Exception {
        assertThat(wire(E.withGmailRaw("has:attachment"))).isEqualTo("X-GM-RAW has:attachment");
        assertThat(wire(E.withGmailRaw("has:attachment larger:5M"))).isEqualTo("X-GM-RAW \"has:attachment larger:5M\"");
    }

    @Test
    void quotesAndBackslashesAreEscaped() throws Exception {
        assertThat(wire(E.withGmailRaw("subject:\"a b\" from:c\\d"))).isEqualTo("X-GM-RAW \"subject:\\\"a b\\\" from:c\\\\d\"");
    }

    @Test
    void combinedWithOtherCriteriaByAnd() throws Exception {
        String w = wire(E.withGmailRaw("has:attachment").withSubject("x"));
        assertThat(w).contains("X-GM-RAW has:attachment").contains("SUBJECT x");
    }
}
