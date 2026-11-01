package de.alexanderlindhorst.imapmcp.core.search;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/**
 * Search filters as received by search_messages, without folder, page_size and cursor. The resolved folder
 * full name is supplied separately to {@link QueryHash#of}. Null means absent; booleans are nullable so that
 * absent and false stay distinct.
 */
public record SearchCriteria(String from, String to, String subject, String text, LocalDate dateFrom,
        LocalDate dateTo, Instant since, Boolean seen, Boolean flagged, Boolean answered, Boolean draft,
        List<String> keywords, String gmailRaw) {

    public SearchCriteria {
        keywords = keywords == null ? null : List.copyOf(keywords);
    }

    public static SearchCriteria empty() {
        return new SearchCriteria(null, null, null, null, null, null, null, null, null, null, null, null, null);
    }

    public SearchCriteria withFrom(String v) {
        return new SearchCriteria(v, to, subject, text, dateFrom, dateTo, since, seen, flagged, answered, draft, keywords, gmailRaw);
    }

    public SearchCriteria withSubject(String v) {
        return new SearchCriteria(from, to, v, text, dateFrom, dateTo, since, seen, flagged, answered, draft, keywords, gmailRaw);
    }

    public SearchCriteria withSince(Instant v) {
        return new SearchCriteria(from, to, subject, text, dateFrom, dateTo, v, seen, flagged, answered, draft, keywords, gmailRaw);
    }

    public SearchCriteria withSeen(Boolean v) {
        return new SearchCriteria(from, to, subject, text, dateFrom, dateTo, since, v, flagged, answered, draft, keywords, gmailRaw);
    }

    public SearchCriteria withKeywords(List<String> v) {
        return new SearchCriteria(from, to, subject, text, dateFrom, dateTo, since, seen, flagged, answered, draft, v, gmailRaw);
    }
}
