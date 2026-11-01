package de.alexanderlindhorst.imapmcp.core.mime;

import de.alexanderlindhorst.imapmcp.core.content.UntrustedText;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Element;
import org.jsoup.nodes.Node;
import org.jsoup.nodes.TextNode;
import org.jsoup.select.NodeFilter;
import org.jsoup.select.NodeTraversor;

/**
 * HTML to plain text for untrusted mail bodies (SPEC 4, 5.3). Pure, deterministic, no network: the document is parsed
 * without a base URI and nothing is ever fetched.
 *
 * <p>Block rule: paragraph-level elements (p, h1-h6, blockquote, ul, ol, table, pre, hr) are separated from their
 * neighbours by one blank line; line-level elements (div, li, tr, section, ... and {@code <br>}) by one line break.
 * Adjacent requests merge instead of adding up (a {@code <br>} adds one break; at most one blank line is ever
 * produced). Whitespace runs collapse to a single space everywhere, including inside {@code <pre>}.
 *
 * <p>Dropped with all descendants: comments, script, style, head, title, template, noscript, elements with the
 * {@code hidden} attribute, and elements whose inline style hides them (display:none, visibility:hidden/collapse,
 * font-size:0, and opacity:0, which renders text invisibly just like the others). Hidden text is a classic prompt
 * injection vector. Images yield their alt text only. Links render as {@code text <url>} for http, https and mailto
 * only (scheme case-insensitive, URL containing whitespace, '<' or '>' is never shown); a link whose text equals the
 * URL, or has no text, shows the bare URL once.
 */
public final class HtmlToText {

    private static final Set<String> DROPPED = Set.of("script", "style", "head", "title", "template", "noscript");
    private static final Set<String> PARAGRAPH = Set.of("p", "h1", "h2", "h3", "h4", "h5", "h6", "blockquote", "ul",
            "ol", "table", "pre", "hr");
    private static final Set<String> LINE = Set.of("div", "li", "tr", "section", "article", "header", "footer", "nav",
            "aside", "main", "dl", "dt", "dd", "figure", "figcaption", "address", "form", "fieldset", "caption",
            "body", "html");
    private static final Set<String> CELL = Set.of("td", "th");

    private static final String LENGTH = "(?:0+(?:\\.0*)?|\\.0+)";
    private static final Pattern HIDING_STYLE = Pattern.compile("(?:^|;)(?:display:none|visibility:(?:hidden|collapse)"
            + "|font-size:" + LENGTH + "(?:[a-z%]+)?|opacity:" + LENGTH + "%?)(?=;|!|$)");

    private HtmlToText() {
    }

    public static String convert(String html) {
        if (html == null || html.isEmpty()) {
            return "";
        }
        Converter c = new Converter();
        NodeTraversor.filter(c, Jsoup.parse(html));
        return UntrustedText.bodyText(c.out.toString());
    }

    private static boolean isHidden(Element e) {
        if (DROPPED.contains(e.normalName()) || e.hasAttr("hidden")) {
            return true;
        }
        String style = e.attr("style");
        if (style.isEmpty()) {
            return false;
        }
        StringBuilder sb = new StringBuilder(style.length());
        for (int i = 0; i < style.length(); i++) {
            char ch = style.charAt(i);
            if (!isSpace(ch)) {
                sb.append(Character.toLowerCase(ch));
            }
        }
        return HIDING_STYLE.matcher(sb).find();
    }

    private static boolean isSpace(char ch) {
        return Character.isWhitespace(ch) || ch == '\u00A0';
    }

    private static String showableUrl(String href) {
        String url = href.strip();
        String lower = url.toLowerCase(Locale.ROOT);
        if (!(lower.startsWith("http://") || lower.startsWith("https://") || lower.startsWith("mailto:"))) {
            return null;
        }
        for (int i = 0; i < url.length(); i++) {
            char ch = url.charAt(i);
            if (isSpace(ch) || ch == '<' || ch == '>' || Character.isISOControl(ch)) {
                return null;
            }
        }
        return url;
    }

    private static final class Converter implements NodeFilter {

        final StringBuilder out = new StringBuilder();
        private final Deque<Integer> linkStarts = new ArrayDeque<>();
        private int pendingBreaks;
        private boolean pendingSpace;

        @Override
        public FilterResult head(Node node, int depth) {
            if (node instanceof TextNode t) {
                emit(t.getWholeText());
            } else if (node instanceof Element e) {
                if (isHidden(e)) {
                    return FilterResult.SKIP_ENTIRELY;
                }
                String name = e.normalName();
                if (name.equals("br")) {
                    pendingBreaks = Math.min(2, pendingBreaks + 1);
                } else if (name.equals("img")) {
                    emit(e.attr("alt"));
                } else if (name.equals("a")) {
                    linkStarts.push(out.length());
                } else {
                    block(name);
                }
            }
            return FilterResult.CONTINUE;
        }

        @Override
        public FilterResult tail(Node node, int depth) {
            if (node instanceof Element e) {
                String name = e.normalName();
                if (name.equals("a") && !linkStarts.isEmpty()) {
                    link(e, linkStarts.pop());
                } else if (CELL.contains(name)) {
                    pendingSpace = true;
                } else {
                    block(name);
                }
            }
            return FilterResult.CONTINUE;
        }

        private void block(String name) {
            if (PARAGRAPH.contains(name)) {
                pendingBreaks = 2;
            } else if (LINE.contains(name)) {
                pendingBreaks = Math.max(pendingBreaks, 1);
            }
        }

        private void link(Element a, int start) {
            String url = showableUrl(a.attr("href"));
            if (url == null) {
                return;
            }
            String text = out.substring(start).strip();
            if (text.isEmpty()) {
                emit(url);
            } else if (!text.equals(url)) {
                pendingSpace = true;
                emit("<" + url + ">");
            }
        }

        /** Appends text collapsing whitespace; breaks and spaces are written lazily so none dangle at an edge. */
        private void emit(String s) {
            for (int i = 0; i < s.length(); i++) {
                char ch = s.charAt(i);
                if (isSpace(ch)) {
                    pendingSpace = true;
                    continue;
                }
                if (out.length() > 0) {
                    if (pendingBreaks > 0) {
                        out.append(pendingBreaks >= 2 ? "\n\n" : "\n");
                    } else if (pendingSpace) {
                        out.append(' ');
                    }
                }
                pendingBreaks = 0;
                pendingSpace = false;
                out.append(ch);
            }
        }
    }
}
