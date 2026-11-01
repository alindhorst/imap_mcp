package de.alexanderlindhorst.imapmcp.core.search;

import jakarta.mail.search.SearchTerm;
import java.time.ZoneId;

public final class SearchTermMapper {

    public SearchTermMapper() {
        this(ZoneId.systemDefault());
    }

    public SearchTermMapper(ZoneId zone) {}

    public SearchTerm map(SearchCriteria criteria) {
        throw new UnsupportedOperationException();
    }
}
