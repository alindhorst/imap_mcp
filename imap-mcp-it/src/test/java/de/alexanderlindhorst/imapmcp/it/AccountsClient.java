package de.alexanderlindhorst.imapmcp.it;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
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

    private static final String BROWSER_UA = "Mozilla/5.0 (X11; Linux x86_64; rv:130.0) Gecko/20100101 Firefox/130.0";
    private static final int MAX_HOPS = 8;
    private static final Pattern CSRF = Pattern.compile("name=\"csrf\" value=\"([^\"]+)\"");
    private static final Pattern FORM = Pattern.compile("(?s)<form\\b([^>]*)>(.*?)</form>");
    private static final Pattern ACTION = Pattern.compile("action=\"([^\"]*)\"");

    private final DeployedStack stack;
    private final String base;
    private final String idpHost;
    private final CookieJar jar = new CookieJar();
    private final List<String> bodies = new ArrayList<>();
    private final List<String> cookieValues = new ArrayList<>();
    private String csrf;

    AccountsClient(DeployedStack stack) {
        this.stack = stack;
        this.base = stack.configValue("public", "baseUrl");
        this.idpHost = URI.create(stack.idpOrigin()).getHost();
    }

    /**
     * The browser's sign-in: {@code GET /accounts/login}, Keycloak's login form with the user's credentials, and the
     * callback with the cookies of this browser. Returns the response of the callback (200 with the session cookie
     * and the meta-refresh page, or the refusal); the code and state never leave this method.
     */
    HttpResponse<String> login(KeycloakAdmin.TestUser user) throws IOException, InterruptedException {
        csrf = null;
        HttpResponse<String> start = request("GET", URI.create(base + "/accounts/login"), null, false);
        URI next = URI.create(start.headers().firstValue("Location").orElseThrow());
        HttpResponse<String> page = null;
        for (int hop = 0; hop < MAX_HOPS; hop++) {
            page = request("GET", next, null, false);
            if (page.statusCode() / 100 != 3) {
                break;
            }
            next = next.resolve(page.headers().firstValue("Location").orElseThrow());
        }
        String action = loginFormAction(page.body());
        if (action == null) {
            throw new IllegalStateException("no login form at the identity provider (status " + page.statusCode() + ")");
        }
        HttpResponse<String> posted = request("POST", URI.create(action), form(Map.of("username", user.username(),
                "password", user.password(), "credentialId", "")), true);
        if (posted.statusCode() != 302) {
            throw new IllegalStateException("the identity provider did not accept the login (status "
                    + posted.statusCode() + ")");
        }
        URI back = URI.create(posted.headers().firstValue("Location").orElseThrow());
        if (!back.toString().startsWith(base + "/accounts/callback?")) {
            throw new IllegalStateException("the login did not return to the callback of the linking surface");
        }
        return request("GET", back, null, false);
    }

    HttpResponse<String> get(String path) throws IOException, InterruptedException {
        return request("GET", URI.create(base + path), null, false);
    }

    /** A form {@code POST} with the session's CSRF token and the {@code Origin} of the surface. */
    HttpResponse<String> post(String path, Map<String, String> fields) throws IOException, InterruptedException {
        Map<String, String> all = new LinkedHashMap<>();
        all.put("csrf", csrf());
        all.putAll(fields);
        return request("POST", URI.create(base + path), form(all), false);
    }

    /** The CSRF token of the session, read once from the home page. */
    String csrf() throws IOException, InterruptedException {
        if (csrf == null) {
            csrf = csrfToken(get("/accounts").body());
        }
        return csrf;
    }

    /** Every response body this browser received (the password scan); never printed. */
    List<String> responseBodies() {
        return List.copyOf(bodies);
    }

    /** Every cookie value this browser was given (the log scan); never printed. */
    List<String> cookieValues() {
        return List.copyOf(cookieValues);
    }

    private HttpResponse<String> request(String method, URI uri, String formBody, boolean withIdpOrigin)
            throws IOException, InterruptedException {
        HttpRequest.Builder b = HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(30))
                .header("User-Agent", BROWSER_UA).header("Accept", "text/html,application/xhtml+xml");
        String cookie = jar.header(uri.getHost());
        if (cookie != null) {
            b.header("Cookie", cookie);
        }
        if (formBody != null) {
            boolean idp = uri.getHost().equals(idpHost);
            b.header("Content-Type", "application/x-www-form-urlencoded")
                    .header("Origin", idp ? stack.idpOrigin() : URI.create(base).getScheme() + "://"
                            + URI.create(base).getAuthority())
                    .POST(HttpRequest.BodyPublishers.ofString(formBody));
        } else {
            b.method(method, HttpRequest.BodyPublishers.noBody());
        }
        HttpResponse<String> r = stack.httpClient().send(b.build(), HttpResponse.BodyHandlers.ofString());
        List<String> setCookies = r.headers().allValues("Set-Cookie");
        jar.store(uri.getHost(), setCookies);
        for (String c : setCookies) {
            String value = SetCookie.parse(c).value();
            if (!value.isEmpty()) {
                cookieValues.add(value);
            }
        }
        bodies.add(r.body());
        return r;
    }

    private static String form(Map<String, String> fields) {
        return fields.entrySet().stream().map(e -> URLEncoder.encode(e.getKey(), StandardCharsets.UTF_8) + "="
                + URLEncoder.encode(e.getValue(), StandardCharsets.UTF_8)).collect(Collectors.joining("&"));
    }

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
