package de.alexanderlindhorst.imapmcp.transport.http.web;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import de.alexanderlindhorst.imapmcp.core.account.MailAccount;
import de.alexanderlindhorst.imapmcp.core.account.ResolvedAccount;
import de.alexanderlindhorst.imapmcp.core.account.Secret;
import de.alexanderlindhorst.imapmcp.core.account.SecretRef;
import de.alexanderlindhorst.imapmcp.core.audit.RecordingAuditLog;
import de.alexanderlindhorst.imapmcp.core.linking.AccountLinker;
import de.alexanderlindhorst.imapmcp.core.linking.LinkOutcome;
import de.alexanderlindhorst.imapmcp.core.linking.LinkRequest;
import de.alexanderlindhorst.imapmcp.core.linking.SettingsRequest;
import de.alexanderlindhorst.imapmcp.core.tenancy.AccountId;
import de.alexanderlindhorst.imapmcp.core.tenancy.TenantId;
import de.alexanderlindhorst.imapmcp.core.web.WebSessionStore;
import de.alexanderlindhorst.imapmcp.transport.http.HttpLocalServer;
import java.net.ServerSocket;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

/** The account pages of the linking surface (C2): forms, outcomes, CSRF, tenant scoping, no password echo. */
class LinkingPagesTest {

    static final String PW = "pw-canary-9f3";
    static final TenantId TENANT = new TenantId("00000000-0000-4000-8000-0000000000aa");

    /** Records every call and answers with a scripted outcome. */
    static final class FakeLinker implements AccountLinker {
        final List<String> calls = new CopyOnWriteArrayList<>();
        final List<TenantId> tenantsSeen = new CopyOnWriteArrayList<>();
        final List<ResolvedAccount> accounts = new ArrayList<>();
        volatile LinkOutcome outcome = LinkOutcome.OK;
        volatile boolean purgeEnabled;
        volatile int max = 10;
        LinkRequest lastLink;
        SettingsRequest lastSettings;
        Instant lastAuthTime;

        @Override
        public List<ResolvedAccount> list(TenantId tenant) {
            tenantsSeen.add(tenant);
            return tenant.equals(TENANT) ? List.copyOf(accounts) : List.of();
        }

        @Override
        public Optional<ResolvedAccount> find(TenantId tenant, String handle) {
            tenantsSeen.add(tenant);
            return tenant.equals(TENANT) ? accounts.stream().filter(a -> a.handle().equals(handle)).findFirst()
                    : Optional.empty();
        }

        @Override
        public LinkOutcome link(TenantId tenant, LinkRequest request, char[] password, Instant authTime,
                String requestId) {
            tenantsSeen.add(tenant);
            calls.add("link:" + new String(password));
            lastLink = request;
            lastAuthTime = authTime;
            return outcome;
        }

        @Override
        public LinkOutcome relink(TenantId tenant, String handle, char[] password, String requestId) {
            tenantsSeen.add(tenant);
            calls.add("relink:" + handle + ":" + new String(password));
            return outcome;
        }

        @Override
        public LinkOutcome updateSettings(TenantId tenant, String handle, SettingsRequest request, Instant authTime,
                String requestId) {
            tenantsSeen.add(tenant);
            calls.add("settings:" + handle);
            lastSettings = request;
            lastAuthTime = authTime;
            return outcome;
        }

        @Override
        public LinkOutcome unlink(TenantId tenant, String handle, String requestId) {
            tenantsSeen.add(tenant);
            calls.add("unlink:" + handle);
            return outcome;
        }

        @Override
        public boolean purgeEnabled() {
            return purgeEnabled;
        }

        @Override
        public int maxAccounts() {
            return max;
        }
    }

    static final class Collecting extends ch.qos.logback.core.AppenderBase<ILoggingEvent> {
        final List<ILoggingEvent> list = new CopyOnWriteArrayList<>();

        @Override
        protected void append(ILoggingEvent event) {
            list.add(event);
        }
    }

    final FakeLinker linker = new FakeLinker();
    final InMemoryStores.Sessions sessions = new InMemoryStores.Sessions();
    final HttpClient http = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build();
    HttpLocalServer server;
    int port;
    String origin;
    String cookie;
    String csrf;
    Collecting logs;

    @BeforeEach
    void setUp() throws Exception {
        Logger root = (Logger) LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME);
        logs = new Collecting();
        logs.start();
        root.addAppender(logs);
        root.setLevel(Level.DEBUG);
        linker.accounts.add(account("Work", "acc_aaaaaaaaaaaaaaaaaaaaaaaaaa", MailAccount.Status.ACTIVE, false));
        start();
    }

    @AfterEach
    void tearDown() throws Exception {
        ((Logger) LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME)).detachAppender(logs);
        if (server != null) {
            server.stop();
        }
    }

    static ResolvedAccount account(String label, String handle, MailAccount.Status status, boolean allowPurge) {
        return new ResolvedAccount(TENANT, new MailAccount(new AccountId("acct-" + handle.substring(4, 8)), label,
                null, "imap.example.com", 993, MailAccount.Security.IMPLICIT_TLS, MailAccount.Flavor.GENERIC,
                "me@example.org", new SecretRef("db:"), true, allowPurge, MailAccount.Auth.PASSWORD, status, 3L),
                handle);
    }

    void start() throws Exception {
        if (server != null) {
            server.stop();
        }
        try (ServerSocket s = new ServerSocket(0)) {
            port = s.getLocalPort();
        }
        origin = "https://127.0.0.1:" + port;
        WebConfig config = new WebConfig(URI.create(origin), "https://idp.example/realms/x", "client",
                () -> new Secret("s".toCharArray()), "sub", "imap-user", null, false);
        OidcProvider stub = new OidcProvider() {
            @Override
            public URI authorizationEndpoint() throws OidcException {
                throw new OidcException(OidcException.UNAVAILABLE);
            }

            @Override
            public IdToken exchange(String code, String verifier, String nonce) throws OidcException {
                throw new OidcException(OidcException.UNAVAILABLE);
            }
        };
        server = HttpLocalServer.builder().port(port).webSurface(WebSurface.of(config, stub,
                new InMemoryStores.Pending(), sessions, new InMemoryStores.Tenants(new RecordingAuditLog()), linker,
                Clock.systemUTC())).build();
        server.start();
        WebSessionStore.Created c = sessions.create(TENANT, Instant.now());
        cookie = "__Host-session=" + c.sessionId();
        csrf = c.csrfToken();
    }

    record Resp(int status, Map<String, List<String>> headers, String body) {
        String header(String n) {
            List<String> v = headers.get(n.toLowerCase());
            return v == null || v.isEmpty() ? null : v.get(0);
        }
    }

    Resp send(String method, String path, String cookieHeader, Map<String, String> headers, Map<String, String> form)
            throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path));
        if (cookieHeader != null) {
            b.header("Cookie", cookieHeader);
        }
        headers.forEach(b::header);
        if (form != null) {
            StringBuilder body = new StringBuilder();
            form.forEach((k, v) -> body.append(body.length() == 0 ? "" : "&")
                    .append(URLEncoder.encode(k, StandardCharsets.UTF_8)).append('=')
                    .append(URLEncoder.encode(v, StandardCharsets.UTF_8)));
            b.header("Content-Type", "application/x-www-form-urlencoded");
            b.method(method, HttpRequest.BodyPublishers.ofString(body.toString()));
        } else {
            b.method(method, HttpRequest.BodyPublishers.noBody());
        }
        HttpResponse<String> r = http.send(b.build(), HttpResponse.BodyHandlers.ofString());
        return new Resp(r.statusCode(), r.headers().map(), r.body());
    }

    Resp get(String path) throws Exception {
        return send("GET", path, cookie, Map.of(), null);
    }

    Resp post(String path, Map<String, String> form) throws Exception {
        Map<String, String> withCsrf = new LinkedHashMap<>(form);
        withCsrf.putIfAbsent("csrf", csrf);
        return send("POST", path, cookie, Map.of("Origin", origin), withCsrf);
    }

    static Map<String, String> linkForm() {
        Map<String, String> f = new LinkedHashMap<>();
        f.put("label", "Private");
        f.put("host", "imap.example.com");
        f.put("port", "993");
        f.put("security", "IMPLICIT_TLS");
        f.put("username", "me@example.org");
        f.put("password", PW);
        f.put("access", "read_only");
        return f;
    }

    void assertHeadersAndNoPassword(Resp r) {
        assertThat(r.header("Cache-Control")).isEqualTo("no-store");
        assertThat(r.header("Content-Security-Policy")).isEqualTo(AccountsFlowTest.CSP);
        assertThat(r.body()).doesNotContain(PW).doesNotContain("<script");
    }

    // ---- list ----

    @Test
    void theHomePageListsTheTenantsAccountsEscapedWithItsActions() throws Exception {
        linker.accounts.add(account("<script>alert(1)</script>", "acc_bbbbbbbbbbbbbbbbbbbbbbbbbb",
                MailAccount.Status.NEEDS_RELINK, true));

        Resp r = get("/accounts");

        assertThat(r.status()).isEqualTo(200);
        assertThat(r.body()).contains("Work").contains("&lt;script&gt;alert(1)&lt;/script&gt;")
                .doesNotContain("<script>alert").contains("needs a new password")
                .contains("/accounts/relink?account=acc_bbbbbbbbbbbbbbbbbbbbbbbbbb")
                .contains("/accounts/settings?account=acc_aaaaaaaaaaaaaaaaaaaaaaaaaa")
                .contains("/accounts/unlink?account=acc_aaaaaaaaaaaaaaaaaaaaaaaaaa").contains("/accounts/link");
        assertThat(linker.tenantsSeen).containsOnly(TENANT);
        assertHeadersAndNoPassword(r);
    }

    @Test
    void atTheAccountLimitThereIsNoAddLink() throws Exception {
        linker.max = 1;

        Resp r = get("/accounts");

        assertThat(r.body()).doesNotContain("href=\"/accounts/link\"").contains("limit");
    }

    // ---- link ----

    @Test
    void theLinkFormHasSafeDefaultsAndNeverPrefillsAPassword() throws Exception {
        Resp r = get("/accounts/link");

        assertThat(r.status()).isEqualTo(200);
        assertThat(r.body()).contains("name=\"csrf\" value=\"" + csrf + "\"").contains("action=\"/accounts/link\"")
                .contains("type=\"password\"").contains("autocomplete=\"new-password\"")
                .contains("name=\"port\"").contains("value=\"993\"")
                .contains("<option value=\"read_only\" selected>").doesNotContain("allow_purge");
        assertThat(r.body()).doesNotContainPattern("type=\"password\"[^>]*value=");
        assertHeadersAndNoPassword(r);
    }

    @Test
    void allowPurgeIsOnlyOfferedWhenTheDeploymentEnablesIt() throws Exception {
        linker.purgeEnabled = true;

        Resp r = get("/accounts/link");

        assertThat(r.body()).contains("name=\"allow_purge\"").doesNotContain("name=\"allow_purge\" checked");
    }

    @Test
    void aLinkPostPassesFormValuesAndTheSessionsTenantNotAFormTenant() throws Exception {
        Map<String, String> form = linkForm();
        form.put("tenant", "00000000-0000-4000-8000-0000000000bb");
        form.put("tenant_id", "00000000-0000-4000-8000-0000000000bb");

        Resp r = post("/accounts/link", form);

        assertThat(r.status()).isEqualTo(200);
        assertThat(linker.calls).containsExactly("link:" + PW);
        assertThat(linker.lastLink.label()).isEqualTo("Private");
        assertThat(linker.lastLink.host()).isEqualTo("imap.example.com");
        assertThat(linker.lastLink.port()).isEqualTo(993);
        assertThat(linker.lastLink.security()).isEqualTo(MailAccount.Security.IMPLICIT_TLS);
        assertThat(linker.lastLink.username()).isEqualTo("me@example.org");
        assertThat(linker.lastLink.readOnly()).isTrue();
        assertThat(linker.lastLink.allowPurge()).isFalse();
        assertThat(linker.lastAuthTime).isNotNull();
        assertThat(linker.tenantsSeen).containsOnly(TENANT);
        assertHeadersAndNoPassword(r);
    }

    @Test
    void accessDefaultsToReadOnlyAndOnlyAnExplicitReadWriteIsWritable() throws Exception {
        Map<String, String> noAccess = linkForm();
        noAccess.remove("access");
        post("/accounts/link", noAccess);
        assertThat(linker.lastLink.readOnly()).isTrue();

        Map<String, String> junk = linkForm();
        junk.put("access", "everything");
        post("/accounts/link", junk);
        assertThat(linker.lastLink.readOnly()).isTrue();

        Map<String, String> rw = linkForm();
        rw.put("access", "read_write");
        post("/accounts/link", rw);
        assertThat(linker.lastLink.readOnly()).isFalse();
    }

    @Test
    void badPortAndSecurityValuesBecomeInvalidInputWithoutCallingBeyondTheLinker() throws Exception {
        Map<String, String> badPort = linkForm();
        badPort.put("port", "not-a-number");
        Resp r = post("/accounts/link", badPort);
        Map<String, String> badSec = linkForm();
        badSec.put("security", "NONE");
        Resp r2 = post("/accounts/link", badSec);

        assertThat(r.status()).isEqualTo(400);
        assertThat(r2.status()).isEqualTo(400);
        assertThat(linker.calls).isEmpty();
    }

    @Test
    void everyOutcomeHasItsStatusAndAFixedMessageThatNeverEchoesInput() throws Exception {
        Object[][] rows = {{LinkOutcome.INVALID_INPUT, 400, "check the fields"},
                {LinkOutcome.HOST_REFUSED, 400, "cannot be used"},
                {LinkOutcome.RATE_LIMITED, 429, "too many"},
                {LinkOutcome.PROBE_AUTH_FAILED, 400, "did not accept"},
                {LinkOutcome.PROBE_UNREACHABLE, 502, "could not connect"},
                {LinkOutcome.ACCOUNT_LIMIT, 409, "limit"},
                {LinkOutcome.NOT_FOUND, 404, "not found"},
                {LinkOutcome.REAUTH_REQUIRED, 403, "/accounts/login?reauth=1"},
                {LinkOutcome.PURGE_DISABLED, 403, "not available"},
                {LinkOutcome.UNAVAILABLE, 503, "try again later"}};
        for (Object[] row : rows) {
            linker.outcome = (LinkOutcome) row[0];

            Resp r = post("/accounts/link", linkForm());

            assertThat(r.status()).as(row[0].toString()).isEqualTo(row[1]);
            assertThat(r.body().toLowerCase()).as(row[0].toString()).contains((String) row[2]);
            assertThat(r.body()).doesNotContain("imap.example.com").doesNotContain("me@example.org");
            assertHeadersAndNoPassword(r);
        }
    }

    // ---- re-link ----

    @Test
    void theRelinkFormIsOnlyForTheTenantsOwnAccount() throws Exception {
        Resp own = get("/accounts/relink?account=acc_aaaaaaaaaaaaaaaaaaaaaaaaaa");
        Resp unknown = get("/accounts/relink?account=acc_zzzzzzzzzzzzzzzzzzzzzzzzzz");
        Resp junk = get("/accounts/relink?account=" + URLEncoder.encode("<x>", StandardCharsets.UTF_8));
        Resp none = get("/accounts/relink");

        assertThat(own.status()).isEqualTo(200);
        assertThat(own.body()).contains("type=\"password\"").contains("name=\"account\" value=\"acc_aaaa")
                .contains("action=\"/accounts/relink\"");
        assertThat(unknown.status()).isEqualTo(404);
        assertThat(junk.status()).isEqualTo(404);
        assertThat(none.status()).isEqualTo(404);
        assertThat(unknown.body()).isEqualTo(junk.body()).isEqualTo(none.body());
        assertThat(junk.body()).doesNotContain("<x>");
    }

    @Test
    void aRelinkPostPassesTheHandleAndThePassword() throws Exception {
        Resp r = post("/accounts/relink", Map.of("account", "acc_aaaaaaaaaaaaaaaaaaaaaaaaaa", "password", PW));

        assertThat(r.status()).isEqualTo(200);
        assertThat(linker.calls).containsExactly("relink:acc_aaaaaaaaaaaaaaaaaaaaaaaaaa:" + PW);
        assertHeadersAndNoPassword(r);
    }

    // ---- settings ----

    @Test
    void theSettingsFormShowsTheCurrentValuesEscaped() throws Exception {
        linker.accounts.set(0, account("A \"quoted\" <b>", "acc_aaaaaaaaaaaaaaaaaaaaaaaaaa",
                MailAccount.Status.ACTIVE, false));
        linker.purgeEnabled = true;

        Resp r = get("/accounts/settings?account=acc_aaaaaaaaaaaaaaaaaaaaaaaaaa");

        assertThat(r.status()).isEqualTo(200);
        assertThat(r.body()).contains("value=\"A &quot;quoted&quot; &lt;b&gt;\"").doesNotContain("<b>")
                .contains("<option value=\"read_only\" selected>").contains("name=\"allow_purge\"");
        assertThat(get("/accounts/settings?account=acc_zzzzzzzzzzzzzzzzzzzzzzzzzz").status()).isEqualTo(404);
    }

    @Test
    void aSettingsPostPassesLabelAndFlags() throws Exception {
        Resp r = post("/accounts/settings", Map.of("account", "acc_aaaaaaaaaaaaaaaaaaaaaaaaaa", "label", "New",
                "access", "read_write", "allow_purge", "on"));

        assertThat(r.status()).isEqualTo(200);
        assertThat(linker.calls).containsExactly("settings:acc_aaaaaaaaaaaaaaaaaaaaaaaaaa");
        assertThat(linker.lastSettings).isEqualTo(new SettingsRequest("New", false, true));
        assertThat(linker.lastAuthTime).isNotNull();
    }

    // ---- unlink ----

    @Test
    void unlinkNeedsAConfirmationPostAndAGetNeverDeletes() throws Exception {
        Resp confirm = get("/accounts/unlink?account=acc_aaaaaaaaaaaaaaaaaaaaaaaaaa");

        assertThat(confirm.status()).isEqualTo(200);
        assertThat(confirm.body()).contains("Work").contains("action=\"/accounts/unlink\"")
                .contains("name=\"csrf\" value=\"" + csrf + "\"");
        assertThat(linker.calls).isEmpty();

        Resp done = post("/accounts/unlink", Map.of("account", "acc_aaaaaaaaaaaaaaaaaaaaaaaaaa"));

        assertThat(done.status()).isEqualTo(200);
        assertThat(linker.calls).containsExactly("unlink:acc_aaaaaaaaaaaaaaaaaaaaaaaaaa");
    }

    // ---- guards ----

    @Test
    void everyPostNeedsTheCsrfTokenAndTheSessionsOrigin() throws Exception {
        Map<String, String> form = Map.of("account", "acc_aaaaaaaaaaaaaaaaaaaaaaaaaa", "password", PW, "label", "x");
        for (String path : List.of("/accounts/link", "/accounts/relink", "/accounts/settings", "/accounts/unlink")) {
            assertThat(send("POST", path, cookie, Map.of("Origin", origin), form).status()).as(path).isEqualTo(403);
            Map<String, String> wrong = new LinkedHashMap<>(form);
            wrong.put("csrf", "wrong");
            assertThat(send("POST", path, cookie, Map.of("Origin", origin), wrong).status()).as(path).isEqualTo(403);
            Map<String, String> good = new LinkedHashMap<>(form);
            good.put("csrf", csrf);
            assertThat(send("POST", path, cookie, Map.of("Origin", "https://evil.example"), good).status())
                    .as(path).isEqualTo(403);
            assertThat(send("POST", path, null, Map.of("Origin", origin), good).status()).as(path).isEqualTo(403);
        }
        assertThat(linker.calls).isEmpty();
    }

    @Test
    void thePagesAreForSignedInUsersOnly() throws Exception {
        for (String path : List.of("/accounts/link", "/accounts/relink?account=acc_aaaaaaaaaaaaaaaaaaaaaaaaaa",
                "/accounts/settings?account=acc_aaaaaaaaaaaaaaaaaaaaaaaaaa",
                "/accounts/unlink?account=acc_aaaaaaaaaaaaaaaaaaaaaaaaaa")) {
            Resp r = send("GET", path, null, Map.of(), null);
            assertThat(r.status()).as(path).isEqualTo(302);
            assertThat(r.header("location")).isEqualTo("/accounts/login");
        }
    }

    @Test
    void wrongMethodsAreRefused() throws Exception {
        assertThat(get("/accounts/logout").status()).isEqualTo(405);
        Resp r = send("PUT", "/accounts/link", cookie, Map.of("Origin", origin), Map.of("csrf", csrf));
        assertThat(r.status()).isIn(403, 405);
        assertThat(linker.calls).isEmpty();
    }

    @Test
    void anOversizedFormIsRefusedBeforeTheLinker() throws Exception {
        Map<String, String> big = linkForm();
        big.put("label", "x".repeat(200_000));

        Resp r = post("/accounts/link", big);

        assertThat(r.status()).isGreaterThanOrEqualTo(400);
        assertThat(linker.calls).isEmpty();
    }

    @Test
    void thePasswordNeverReachesTheLogs() throws Exception {
        for (LinkOutcome o : LinkOutcome.values()) {
            linker.outcome = o;
            post("/accounts/link", linkForm());
            post("/accounts/relink", Map.of("account", "acc_aaaaaaaaaaaaaaaaaaaaaaaaaa", "password", PW));
        }

        String all = String.join("\n", logs.list.stream().filter(e -> e.getLoggerName().startsWith("de.alexanderlindhorst"))
                .map(ILoggingEvent::getFormattedMessage).toList());
        assertThat(all).doesNotContain(PW).doesNotContain("me@example.org").doesNotContain("imap.example.com");
    }
}
