package de.alexanderlindhorst.imapmcp.transport.http.web;

/** HTML output for the linking surface: every dynamic value goes through {@link #escape}; no scripts, no styles. */
final class Html {

    private Html() {
    }

    static String escape(String s) {
        StringBuilder sb = new StringBuilder(s.length() + 16);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '&' -> sb.append("&amp;");
                case '<' -> sb.append("&lt;");
                case '>' -> sb.append("&gt;");
                case '"' -> sb.append("&quot;");
                case '\'' -> sb.append("&#39;");
                default -> sb.append(c);
            }
        }
        return sb.toString();
    }

    /** A complete page; {@code head} extras (such as a meta refresh) must be built from trusted constants. */
    static String page(String title, String head, String body) {
        return "<!DOCTYPE html>\n<html lang=\"en\"><head><meta charset=\"utf-8\"><title>" + escape(title)
                + "</title>" + head + "</head><body>" + body + "</body></html>\n";
    }
}
