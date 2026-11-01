package de.alexanderlindhorst.imapmcp.core.search;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.mail.Flags;
import jakarta.mail.search.AndTerm;
import jakarta.mail.search.BodyTerm;
import jakarta.mail.search.ComparisonTerm;
import jakarta.mail.search.FlagTerm;
import jakarta.mail.search.FromStringTerm;
import jakarta.mail.search.ReceivedDateTerm;
import jakarta.mail.search.RecipientStringTerm;
import jakarta.mail.search.SearchTerm;
import jakarta.mail.search.SubjectTerm;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;

class SearchTermMapperTest {

    private static final ZoneId ZONE = ZoneId.of("Pacific/Kiritimati");
    private final SearchTermMapper mapper = new SearchTermMapper(ZONE);
    private static final SearchCriteria E = SearchCriteria.empty();

    @Test
    void noCriteriaMapsToNullMeaningMatchAll() {
        assertThat(mapper.map(E)).isNull();
        // gmail_raw is not mapped here (#29)
    }

    // #28: coarse pre-filter only; the exact instant filter happens after INTERNALDATE is fetched
    @Test
    void sinceMapsToReceivedDateGeOnTheUtcDateMinusOneDayAtLocalNoon() {
        // 2026-03-05T00:30+14:00 is 2026-03-04T10:30Z -> UTC date 03-04 -> SINCE 03-03
        java.time.Instant since = java.time.OffsetDateTime.parse("2026-03-05T00:30:00+14:00").toInstant();
        assertThat(mapper.map(E.withSince(since))).isInstanceOfSatisfying(ReceivedDateTerm.class, t -> {
            assertThat(t.getComparison()).isEqualTo(ComparisonTerm.GE);
            assertThat(ZonedDateTime.ofInstant(t.getDate().toInstant(), ZONE))
                    .isEqualTo(ZonedDateTime.of(2026, 3, 3, 12, 0, 0, 0, ZONE));
        });
        assertThat(mapper.map(E.withSince(since).withSubject("x"))).isInstanceOf(jakarta.mail.search.AndTerm.class);
    }

    @Test
    void textFieldsMapToStringTerms() {
        assertThat(mapper.map(E.withFrom("alice"))).isInstanceOfSatisfying(FromStringTerm.class,
                t -> assertThat(t.getPattern()).isEqualTo("alice"));
        assertThat(mapper.map(E.withSubject("hello"))).isInstanceOf(SubjectTerm.class);
        SearchTerm to = mapper.map(new SearchCriteria(null, "bob", null, null, null, null, null, null, null, null,
                null, null, null));
        assertThat(to).isInstanceOfSatisfying(RecipientStringTerm.class, t -> {
            assertThat(t.getRecipientType()).isEqualTo(jakarta.mail.Message.RecipientType.TO);
            assertThat(t.getPattern()).isEqualTo("bob");
        });
        SearchTerm text = mapper.map(new SearchCriteria(null, null, null, "needle", null, null, null, null, null,
                null, null, null, null));
        assertThat(text).isInstanceOf(BodyTerm.class);
    }

    @Test
    void booleansMapToFlagTerms() {
        assertFlag(mapper.map(E.withSeen(true)), Flags.Flag.SEEN, true);
        assertFlag(mapper.map(E.withSeen(false)), Flags.Flag.SEEN, false);
        SearchTerm all = mapper.map(new SearchCriteria(null, null, null, null, null, null, null, null, true, false,
                true, null, null));
        List<SearchTerm> parts = List.of(((AndTerm) all).getTerms());
        assertThat(parts).hasSize(3);
        assertFlag(parts.get(0), Flags.Flag.FLAGGED, true);
        assertFlag(parts.get(1), Flags.Flag.ANSWERED, false);
        assertFlag(parts.get(2), Flags.Flag.DRAFT, true);
    }

    @Test
    void eachKeywordIsARequiredUserFlag() {
        SearchTerm t = mapper.map(E.withKeywords(List.of("a", "b")));
        SearchTerm[] terms = ((AndTerm) t).getTerms();
        assertThat(terms).hasSize(2);
        for (SearchTerm term : terms) {
            FlagTerm f = (FlagTerm) term;
            assertThat(f.getTestSet()).isTrue();
            assertThat(f.getFlags().getUserFlags()).hasSize(1);
        }
    }

    @Test
    void dateBoundsUseLocalNoonOfTheDayInTheZone() {
        SearchCriteria c = new SearchCriteria(null, null, null, null, LocalDate.of(2026, 3, 5),
                LocalDate.of(2026, 3, 5), null, null, null, null, null, null, null);
        SearchTerm[] terms = ((AndTerm) mapper.map(c)).getTerms();
        ReceivedDateTerm since = (ReceivedDateTerm) terms[0];
        ReceivedDateTerm before = (ReceivedDateTerm) terms[1];
        assertThat(since.getComparison()).isEqualTo(ComparisonTerm.GE);
        assertThat(before.getComparison()).isEqualTo(ComparisonTerm.LT);
        assertThat(ZonedDateTime.ofInstant(since.getDate().toInstant(), ZONE))
                .isEqualTo(ZonedDateTime.of(2026, 3, 5, 12, 0, 0, 0, ZONE));
        assertThat(ZonedDateTime.ofInstant(before.getDate().toInstant(), ZONE))
                .isEqualTo(ZonedDateTime.of(2026, 3, 6, 12, 0, 0, 0, ZONE));
    }

    @Test
    void singleCriterionIsNotWrapped() {
        assertThat(mapper.map(E.withFrom("x"))).isNotInstanceOf(AndTerm.class);
    }

    private static void assertFlag(SearchTerm t, Flags.Flag flag, boolean set) {
        FlagTerm f = (FlagTerm) t;
        assertThat(f.getTestSet()).isEqualTo(set);
        assertThat(f.getFlags().contains(flag)).isTrue();
    }
}
