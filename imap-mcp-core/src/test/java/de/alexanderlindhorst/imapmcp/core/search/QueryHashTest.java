package de.alexanderlindhorst.imapmcp.core.search;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;

class QueryHashTest {

    private static final SearchCriteria EMPTY = SearchCriteria.empty();

    private static byte[] h(SearchCriteria c) {
        return QueryHash.of(c, "INBOX");
    }

    @Test
    void hasEightBytesAndIsDeterministic() {
        assertThat(h(EMPTY)).hasSize(8).isEqualTo(h(SearchCriteria.empty()));
    }

    @Test
    void trimsStrings() {
        assertThat(h(EMPTY.withSubject("  Foo"))).isEqualTo(h(EMPTY.withSubject("Foo")));
        assertThat(h(EMPTY.withSubject("Foo\t\n "))).isEqualTo(h(EMPTY.withSubject("Foo")));
    }

    @Test
    void normalizesToNfcButPreservesCase() {
        assertThat(h(EMPTY.withFrom("e\u0301"))).isEqualTo(h(EMPTY.withFrom("\u00e9")));
        assertThat(h(EMPTY.withFrom("Foo"))).isNotEqualTo(h(EMPTY.withFrom("foo")));
    }

    @Test
    void keywordsAreSortedAndDeduplicated() {
        assertThat(h(EMPTY.withKeywords(List.of("b", "a", "b")))).isEqualTo(h(EMPTY.withKeywords(List.of("a", "b"))));
        assertThat(h(EMPTY.withKeywords(List.of(" a", "e\u0301")))).isEqualTo(h(EMPTY.withKeywords(List.of("\u00e9", "a"))));
        assertThat(h(EMPTY.withKeywords(List.of("a")))).isNotEqualTo(h(EMPTY.withKeywords(List.of("a", "b"))));
    }

    @Test
    void absentAndFalseBooleansDiffer() {
        assertThat(h(EMPTY.withSeen(false))).isNotEqualTo(h(EMPTY));
        assertThat(h(EMPTY.withSeen(false))).isNotEqualTo(h(EMPTY.withSeen(true)));
    }

    @Test
    void sinceIsHashedAsUtcEpochSeconds() {
        Instant plus2 = OffsetDateTime.parse("2026-03-01T12:00:00+02:00").toInstant();
        Instant z = OffsetDateTime.parse("2026-03-01T10:00:00Z").toInstant();
        assertThat(h(EMPTY.withSince(plus2))).isEqualTo(h(EMPTY.withSince(z)));
        assertThat(h(EMPTY.withSince(z))).isNotEqualTo(h(EMPTY.withSince(z.plusSeconds(1))));
        assertThat(h(EMPTY.withSince(z.plusMillis(900)))).isEqualTo(h(EMPTY.withSince(z)));
    }

    @Test
    void datesAreIsoLocalDates() {
        SearchCriteria a = new SearchCriteria(null, null, null, null, java.time.LocalDate.of(2026, 1, 2), null,
                null, null, null, null, null, null, null);
        SearchCriteria b = new SearchCriteria(null, null, null, null, null, java.time.LocalDate.of(2026, 1, 2),
                null, null, null, null, null, null, null);
        assertThat(h(a)).isNotEqualTo(h(b)).isNotEqualTo(h(EMPTY));
    }

    @Test
    void fieldsAreNotInterchangeable() {
        assertThat(h(EMPTY.withFrom("x"))).isNotEqualTo(h(EMPTY.withSubject("x")));
    }

    @Test
    void resolvedFolderIsPartOfTheHash() {
        assertThat(QueryHash.of(EMPTY, "INBOX")).isNotEqualTo(QueryHash.of(EMPTY, "Sent"));
        assertThat(QueryHash.of(EMPTY, "Sent")).isEqualTo(QueryHash.of(SearchCriteria.empty(), "Sent"));
    }

    @Test
    void canonicalJsonIsStableAndSorted() {
        SearchCriteria c = EMPTY.withSubject("a\"b\\c\n").withSeen(true).withKeywords(List.of("k"));
        assertThat(QueryHash.canonicalJson(c, "INBOX")).isEqualTo(
                "{\"folder\":\"INBOX\",\"keywords\":[\"k\"],\"seen\":true,\"subject\":\"a\\\"b\\\\c\\n\"}");
        assertThat(QueryHash.canonicalJson(EMPTY.withSubject("\u0001\u00e9"), "F")).isEqualTo(
                "{\"folder\":\"F\",\"subject\":\"\\u0001\u00e9\"}");
    }
}
