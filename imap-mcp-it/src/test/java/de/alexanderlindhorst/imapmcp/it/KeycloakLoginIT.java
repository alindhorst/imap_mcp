package de.alexanderlindhorst.imapmcp.it;

import static org.assertj.core.api.Assertions.assertThat;

import com.nimbusds.jose.jwk.source.JWKSourceBuilder;
import com.nimbusds.jose.proc.SecurityContext;
import de.alexanderlindhorst.imapmcp.core.account.Secret;
import de.alexanderlindhorst.imapmcp.store.jdbc.Database;
import de.alexanderlindhorst.imapmcp.store.jdbc.JdbcOidcPendingStore;
import de.alexanderlindhorst.imapmcp.store.jdbc.JdbcTenantDirectory;
import de.alexanderlindhorst.imapmcp.store.jdbc.JdbcTenantProvisioner;
import de.alexanderlindhorst.imapmcp.store.jdbc.JdbcWebSessionStore;
import de.alexanderlindhorst.imapmcp.transport.http.HttpLocalServer;
import de.alexanderlindhorst.imapmcp.transport.http.web.HttpOidcProvider;
import de.alexanderlindhorst.imapmcp.transport.http.web.WebConfig;
import de.alexanderlindhorst.imapmcp.transport.http.web.WebSurface;
import java.net.ServerSocket;
import java.net.URI;
import java.net.URLEncoder;
import java.net.URL;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * The OIDC login of the linking surface against a real Keycloak, with the JDBC stores on Postgres (C1, SPEC 5.8):
 * the "browser" is an HTTP client that follows the authorization redirect, fills in Keycloak's login form and
 * delivers the code to our callback.
 */
class KeycloakLoginIT {

    private static KeycloakFixture keycloak;
    private static PostgresFixture postgres;
    private static Database db;
    private static HttpLocalServer server;
    private static int port;
    private static String origin;

    @BeforeAll
    static void start() throws Exception {
        keycloak = new KeycloakFixture();
        postgres = new PostgresFixture();
        // Both at once: Keycloak needs a long time to start, and a container created after that wait used to hit a
        // Docker socket connection that the engine (Podman under load) had closed meanwhile ("broken pipe").
        org.testcontainers.lifecycle.Startables.deepStart(keycloak, postgres).join();
        db = Database.open(postgres.config());
        try (ServerSocket s = new ServerSocket(0)) {
            port = s.getLocalPort();
        }
        origin = "https://127.0.0.1:" + port;
        WebConfig config = new WebConfig(URI.create(origin), keycloak.issuer(), KeycloakFixture.CLIENT_ID,
                () -> new Secret(KeycloakFixture.CLIENT_SECRET.toCharArray()), "sub", "imap-user",
                "realm_access.roles", false, true);
        com.nimbusds.jose.jwk.source.JWKSource<SecurityContext> keys =
                JWKSourceBuilder.<SecurityContext>create(new URL(keycloak.certsUrl())).build();
        Clock clock = Clock.systemUTC();
        server = HttpLocalServer.builder().port(port).webSurface(WebSurface.of(config,
                HttpOidcProvider.create(config, keys), new JdbcOidcPendingStore(db.dataSource(), clock),
                new JdbcWebSessionStore(db.dataSource(), clock),
                new JdbcTenantProvisioner(new JdbcTenantDirectory(db.dataSource())), clock)).build();
        server.start();
    }

    @AfterAll
    static void stop() throws Exception {
        if (server != null) {
            server.stop();
        }
        if (db != null) {
            db.close();
        }
        postgres.stop();
        keycloak.stop();
    }

    private final HttpClient noRedirect = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build();

    private HttpResponse<String> ours(String pathAndQuery, String cookie) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + pathAndQuery));
        if (cookie != null) {
            b.header("Cookie", cookie);
        }
        return noRedirect.send(b.GET().build(), HttpResponse.BodyHandlers.ofString());
    }

    /** What the browser ends up with after the IdP step: the callback URL (path and query) or null. */
    private record IdpResult(String callbackPathAndQuery, String bindingCookie, String loginPage) {
    }

    /** Starts a login at our server and plays the browser at Keycloak's login form. */
    private IdpResult browserLogin(String username, String password) throws Exception {
        HttpResponse<String> start = ours("/accounts/login", null);
        assertThat(start.statusCode()).isEqualTo(302);
        String binding = start.headers().firstValue("set-cookie").orElseThrow().split(";")[0];
        // Keycloak marks its cookies Secure even over http, which a CookieManager would not send back: hand-made jar.
        Map<String, String> jar = new java.util.LinkedHashMap<>();
        HttpClient kc = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build();
        HttpResponse<String> page = kc.send(HttpRequest.newBuilder(URI.create(
                start.headers().firstValue("location").orElseThrow())).GET().build(),
                HttpResponse.BodyHandlers.ofString());
        remember(jar, page);
        assertThat(page.statusCode()).isEqualTo(200);
        Matcher m = Pattern.compile("action=\"([^\"]+)\"").matcher(page.body());
        assertThat(m.find()).as("login form").isTrue();
        String action = m.group(1).replace("&amp;", "&");
        String form = "username=" + enc(username) + "&password=" + enc(password) + "&credentialId=";
        HttpResponse<String> posted = kc.send(HttpRequest.newBuilder(URI.create(action))
                .header("Cookie", cookieHeader(jar)).header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(form)).build(), HttpResponse.BodyHandlers.ofString());
        if (posted.statusCode() != 302) {
            return new IdpResult(null, binding, posted.body());
        }
        URI back = URI.create(posted.headers().firstValue("location").orElseThrow());
        assertThat(back.toString()).startsWith(origin + "/accounts/callback?");
        return new IdpResult(back.getRawPath() + "?" + back.getRawQuery(), binding, null);
    }

    private static void remember(Map<String, String> jar, HttpResponse<?> r) {
        for (String c : r.headers().allValues("set-cookie")) {
            String pair = c.split(";")[0];
            int eq = pair.indexOf('=');
            jar.put(pair.substring(0, eq), pair.substring(eq + 1));
        }
    }

    private static String cookieHeader(Map<String, String> jar) {
        return jar.entrySet().stream().map(e -> e.getKey() + "=" + e.getValue())
                .collect(java.util.stream.Collectors.joining("; "));
    }

    private static String enc(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8);
    }

    private static int count(String sql, Object... params) throws Exception {
        try (Connection c = db.dataSource().getConnection(); var ps = c.prepareStatement(sql)) {
            for (int i = 0; i < params.length; i++) {
                ps.setObject(i + 1, params[i]);
            }
            try (var rs = ps.executeQuery()) {
                rs.next();
                return rs.getInt(1);
            }
        }
    }

    @Test
    void aUserWithTheRoleLogsInGetsATenantAndASessionAndCanLogOut() throws Exception {
        IdpResult idp = browserLogin(KeycloakFixture.ALICE, KeycloakFixture.ALICE_PASSWORD);

        HttpResponse<String> callback = ours(idp.callbackPathAndQuery(), idp.bindingCookie());

        assertThat(callback.statusCode()).isEqualTo(200);
        List<String> cookies = callback.headers().allValues("set-cookie");
        String session = cookies.stream().filter(c -> c.startsWith("__Host-session=")).findFirst().orElseThrow();
        assertThat(session).contains("Secure").contains("HttpOnly").contains("SameSite=Strict").contains("Path=/")
                .doesNotContain("Domain");
        assertThat(count("SELECT count(*) FROM tenant WHERE issuer = ?", keycloak.issuer())).isGreaterThanOrEqualTo(1);
        String sessionCookie = session.split(";")[0];
        HttpResponse<String> home = ours("/accounts", sessionCookie);
        assertThat(home.statusCode()).isEqualTo(200);
        Matcher csrf = Pattern.compile("name=\"csrf\" value=\"([^\"]+)\"").matcher(home.body());
        assertThat(csrf.find()).isTrue();
        HttpResponse<String> out = noRedirect.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port
                + "/accounts/logout")).header("Cookie", sessionCookie).header("Origin", origin)
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString("csrf=" + csrf.group(1))).build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(out.statusCode()).isEqualTo(200);
        assertThat(ours("/accounts", sessionCookie).statusCode()).isEqualTo(302);
    }

    @Test
    void aUserWithoutTheRoleGetsNoTenantAndNoSession() throws Exception {
        int before = count("SELECT count(*) FROM tenant");
        IdpResult idp = browserLogin(KeycloakFixture.BOB, KeycloakFixture.BOB_PASSWORD);

        HttpResponse<String> callback = ours(idp.callbackPathAndQuery(), idp.bindingCookie());

        assertThat(callback.statusCode()).isEqualTo(403);
        assertThat(callback.headers().allValues("set-cookie")).noneMatch(c -> c.startsWith("__Host-session=")
                && !c.startsWith("__Host-session=;"));
        assertThat(count("SELECT count(*) FROM tenant")).isEqualTo(before);
    }

    @Test
    void aWrongPasswordNeverReachesOurCallback() throws Exception {
        IdpResult idp = browserLogin(KeycloakFixture.ALICE, "not-the-password");

        assertThat(idp.callbackPathAndQuery()).isNull();
        assertThat(idp.loginPage()).isNotBlank();
    }

    @Test
    void aReplayedCallbackIsRefused() throws Exception {
        IdpResult idp = browserLogin(KeycloakFixture.ALICE, KeycloakFixture.ALICE_PASSWORD);
        assertThat(ours(idp.callbackPathAndQuery(), idp.bindingCookie()).statusCode()).isEqualTo(200);

        assertThat(ours(idp.callbackPathAndQuery(), idp.bindingCookie()).statusCode()).isEqualTo(400);
    }

    @Test
    void aCallbackWithoutTheBindingCookieOfTheBrowserIsRefused() throws Exception {
        IdpResult idp = browserLogin(KeycloakFixture.ALICE, KeycloakFixture.ALICE_PASSWORD);

        assertThat(ours(idp.callbackPathAndQuery(), null).statusCode()).isEqualTo(400);
    }
}
