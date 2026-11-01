package de.alexanderlindhorst.imapmcp.core.search;

import de.alexanderlindhorst.imapmcp.core.error.ToolError;
import java.util.Map;

public final class SearchArguments {

    public static final int DEFAULT_PAGE_SIZE = 25;

    public record Parsed(SearchCriteria criteria, String folder, String folderRole, int pageSize, String cursor,
            ToolError error) {
        public boolean valid() {
            return error == null;
        }
    }

    private SearchArguments() {}

    public static Parsed parse(Map<String, Object> args, String requestId) {
        throw new UnsupportedOperationException();
    }
}
