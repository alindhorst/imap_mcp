package de.alexanderlindhorst.imapmcp.core.search;

import jakarta.mail.Flags;
import jakarta.mail.Message;
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
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import org.eclipse.angus.mail.gimap.GmailRawSearchTerm;

/**
 * Maps {@link SearchCriteria} to a Jakarta Mail {@link SearchTerm}, all criteria ANDed. Returns {@code null}
 * when no criterion maps to a term: the caller then searches ALL. {@code since} yields only the coarse
 * day bound; {@code gmail_raw} becomes a
 * {@link GmailRawSearchTerm} (the capability gate is the tool's job). Terms are client data and never logged.
 *
 * <p>text maps to BODY (not TEXT) so header matches do not leak into body search. Date bounds are INTERNALDATE
 * based: date_from is SINCE (inclusive), date_to is BEFORE date_to+1. Angus formats the date in the JVM
 * default zone, so the Date is built at local noon of the day in {@code zone} (default: the JVM zone) which
 * always yields the intended calendar date. Caveat: this only holds when {@code zone} equals the JVM default
 * zone (the default constructor guarantees it); a different injected zone is for tests that also set the
 * JVM default. The mail server interprets it in its own timezone.
 */
public final class SearchTermMapper {

    private final ZoneId zone;

    public SearchTermMapper() {
        this(ZoneId.systemDefault());
    }

    public SearchTermMapper(ZoneId zone) {
        this.zone = zone;
    }

    public SearchTerm map(SearchCriteria c) {
        List<SearchTerm> terms = new ArrayList<>();
        if (c.from() != null) {
            terms.add(new FromStringTerm(c.from()));
        }
        if (c.to() != null) {
            terms.add(new RecipientStringTerm(Message.RecipientType.TO, c.to()));
        }
        if (c.subject() != null) {
            terms.add(new SubjectTerm(c.subject()));
        }
        if (c.text() != null) {
            terms.add(new BodyTerm(c.text()));
        }
        flag(terms, Flags.Flag.SEEN, c.seen());
        flag(terms, Flags.Flag.FLAGGED, c.flagged());
        flag(terms, Flags.Flag.ANSWERED, c.answered());
        flag(terms, Flags.Flag.DRAFT, c.draft());
        if (c.keywords() != null) {
            for (String k : c.keywords()) {
                terms.add(new FlagTerm(new Flags(k), true));
            }
        }
        if (c.dateFrom() != null) {
            terms.add(new ReceivedDateTerm(ComparisonTerm.GE, noon(c.dateFrom())));
        }
        if (c.dateTo() != null) {
            terms.add(new ReceivedDateTerm(ComparisonTerm.LT, noon(c.dateTo().plusDays(1))));
        }
        if (c.gmailRaw() != null) {
            // Only the gimaps protocol can serialize it; SearchMessagesTool gates on X-GM-EXT-1 beforehand.
            terms.add(new GmailRawSearchTerm(c.gmailRaw()));
        }
        if (c.since() != null) {
            // IMAP SINCE compares the stored calendar date (any zone offset of the INTERNALDATE), so the UTC date
            // minus one day is a superset of every message at or after the instant; PageFetcher then filters
            // exactly on INTERNALDATE.
            LocalDate utcDay = c.since().atZone(ZoneOffset.UTC).toLocalDate();
            terms.add(new ReceivedDateTerm(ComparisonTerm.GE, noon(utcDay.minusDays(1))));
        }
        return switch (terms.size()) {
            case 0 -> null;
            case 1 -> terms.get(0);
            default -> new AndTerm(terms.toArray(SearchTerm[]::new));
        };
    }

    private static void flag(List<SearchTerm> terms, Flags.Flag flag, Boolean value) {
        if (value != null) {
            terms.add(new FlagTerm(new Flags(flag), value));
        }
    }

    private Date noon(LocalDate day) {
        return Date.from(day.atTime(12, 0).atZone(zone).toInstant());
    }
}
