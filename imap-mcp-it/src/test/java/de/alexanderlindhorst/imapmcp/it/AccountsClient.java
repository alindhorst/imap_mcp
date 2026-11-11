package de.alexanderlindhorst.imapmcp.it;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * A browser for the linking surface of the deployed stack ({@code /accounts}, SPEC 5.3): OIDC login at Keycloak with
 * the client {@code imap-mcp-web}, then the pages with the session cookie and the CSRF token. Every request goes
 * through the stack's forward proxy and Caddy. Not thread-safe; one instance per user (it holds that user's cookies).
 */
final class AccountsClient {

    private static final Pattern CSRF = Pattern.compile("name=\"csrf\" value=\"([^\"]+)\"");
    private static final Pattern FORM = Pattern.compile("(?s)<form\\b([^>]*)>(.*?)</form>");
    private static final Pattern ACTION = Pattern.compile("action=\"([^\"]*)\"");

    /** The cookies a browser holds, per host (the stack's cookies are host-only: {@code __Host-} or no Domain). */
    static final class CookieJar {
        private final Map<String, Map<String, String>> byHost = new LinkedHashMap<>();

        void store(String host, List<String> setCookieHeaders) {
            for (String header : setCookieHeaders) {
                SetCookie c = SetCookie.parse(header);
                Map<String, String> cookies = byHost.computeIfAbsent(host, h -> new LinkedHashMap<>());
                if (c.value().isEmpty() || "0".equals(c.attribute("Max-Age"))) {
                    cookies.remove(c.name());
                } else {
                    cookies.put(c.name(), c.value());
                }
            }
        }

        /** The {@code Cookie} header value for the host, or null when there is none. */
        String header(String host) {
            Map<String, String> cookies = byHost.get(host);
            if (cookies == null || cookies.isEmpty()) {
                return null;
            }
            return cookies.entrySet().stream().map(e -> e.getKey() + "=" + e.getValue())
                    .collect(Collectors.joining("; "));
        }
    }

    /** One {@code Set-Cookie} header: the pair and its attributes (names compared case-insensitively). */
    record SetCookie(String name, String value, Map<String, String> attributes) {

        static SetCookie parse(String header) {
            String[] parts = header.split(";");
            String pair = parts[0].trim();
            int eq = pair.indexOf('=');
            Map<String, String> attributes = new LinkedHashMap<>();
            for (int i = 1; i < parts.length; i++) {
                String a = parts[i].trim();
                int e = a.indexOf('=');
                attributes.put((e < 0 ? a : a.substring(0, e)).toLowerCase(Locale.ROOT), e < 0 ? "" : a.substring(e + 1));
            }
            return new SetCookie(eq < 0 ? pair : pair.substring(0, eq), eq < 0 ? "" : pair.substring(eq + 1), attributes);
        }

        boolean hasFlag(String flag) {
            return attributes.containsKey(flag.toLowerCase(Locale.ROOT));
        }

        /** The value of an attribute with a value, or null when it is absent. */
        String attribute(String name) {
            return attributes.get(name.toLowerCase(Locale.ROOT));
        }
    }

    /** The CSRF token of a page of the surface, or null. */
    static String csrfToken(String html) {
        Matcher m = CSRF.matcher(html);
        return m.find() ? m.group(1) : null;
    }

    /** The action of the first form that has a password field (the IdP's login form), or null. */
    static String loginFormAction(String html) {
        Matcher forms = FORM.matcher(html);
        while (forms.find()) {
            if (forms.group(2).contains("name=\"password\"")) {
                Matcher a = ACTION.matcher(forms.group(1));
                return a.find() ? a.group(1).replace("&amp;", "&") : null;
            }
        }
        return null;
    }
}
