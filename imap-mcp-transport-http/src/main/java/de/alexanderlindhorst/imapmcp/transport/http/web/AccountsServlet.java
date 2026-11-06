package de.alexanderlindhorst.imapmcp.transport.http.web;

import de.alexanderlindhorst.imapmcp.core.audit.AuditWriteException;
import de.alexanderlindhorst.imapmcp.core.linking.AccountLinker;
import de.alexanderlindhorst.imapmcp.core.web.OidcPendingStore;
import de.alexanderlindhorst.imapmcp.core.web.TenantProvisioner;
import de.alexanderlindhorst.imapmcp.core.web.WebSession;
import de.alexanderlindhorst.imapmcp.core.web.WebSessionStore;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.util.Base64;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The linking surface (SPEC 5.3, D4): server-rendered pages without JavaScript under {@code /accounts}. This class
 * has the sign-in (OIDC authorization code with PKCE S256, {@code state} and {@code nonce}), the callback that
 * derives the tenant from the ID token, the home page and logout; the account pages are added by later tickets.
 * Session and CSRF checks happen in {@link WebSessionFilter}, headers and Host/Origin in {@link WebSecurityFilter}.
 * Nothing about a login (state, code, tokens, subject, error text of the IdP) is logged or shown.
 */
public final class AccountsServlet extends HttpServlet {

    static final String BINDING_COOKIE = "__Host-oidc";
    private static final int MAX_PARAM = 2048;
    private static final Logger LOG = LoggerFactory.getLogger(AccountsServlet.class);

    private final WebConfig config;
    private final OidcProvider provider;
    private final OidcPendingStore pending;
    private final WebSessionStore sessions;
    private final TenantProvisioner tenants;
    private final LinkingPages pages;
    private final Clock clock;
    private final SecureRandom random = new SecureRandom();

    public AccountsServlet(WebConfig config, OidcProvider provider, OidcPendingStore pending,
            WebSessionStore sessions, TenantProvisioner tenants, AccountLinker linker, Clock clock) {
        this.config = config;
        this.provider = provider;
        this.pending = pending;
        this.sessions = sessions;
        this.tenants = tenants;
        this.pages = new LinkingPages(linker);
        this.clock = clock;
    }

    @Override
    protected void service(HttpServletRequest req, HttpServletResponse res) throws ServletException, IOException {
        WebSecurityFilter.applyHeaders(res); // also when the servlet is used without the filter chain
        String path = req.getRequestURI();
        boolean read = "GET".equals(req.getMethod()) || "HEAD".equals(req.getMethod());
        switch (path) {
            case WebConfig.BASE_PATH, WebConfig.BASE_PATH + "/" -> {
                if (read) {
                    home(req, res);
                } else {
                    methodNotAllowed(res);
                }
            }
            case WebSessionFilter.LOGIN_PATH -> {
                if ("GET".equals(req.getMethod())) { // HEAD must not start a login
                    login(req, res);
                } else {
                    methodNotAllowed(res);
                }
            }
            case WebSessionFilter.CALLBACK_PATH -> {
                if ("GET".equals(req.getMethod())) {
                    callback(req, res);
                } else {
                    methodNotAllowed(res);
                }
            }
            case WebConfig.BASE_PATH + "/logout" -> {
                if ("POST".equals(req.getMethod())) {
                    logout(req, res);
                } else {
                    methodNotAllowed(res);
                }
            }
            case WebConfig.BASE_PATH + "/link" -> accountPage(req, res, (s) -> pages.linkForm(s, res),
                    (s) -> pages.linkSubmit(s, req, res));
            case WebConfig.BASE_PATH + "/relink" -> accountPage(req, res, (s) -> pages.relinkForm(s, req, res),
                    (s) -> pages.relinkSubmit(s, req, res));
            case WebConfig.BASE_PATH + "/settings" -> accountPage(req, res, (s) -> pages.settingsForm(s, req, res),
                    (s) -> pages.settingsSubmit(s, req, res));
            case WebConfig.BASE_PATH + "/unlink" -> accountPage(req, res, (s) -> pages.unlinkForm(s, req, res),
                    (s) -> pages.unlinkSubmit(s, req, res));
            default -> writePage(res, HttpServletResponse.SC_NOT_FOUND, "Not found", "<p>Not found.</p>");
        }
    }

    /** Page action with the session of the request (set by the session filter; without it no page is served). */
    @FunctionalInterface
    private interface PageAction {
        void run(WebSession session) throws IOException;
    }

    private void accountPage(HttpServletRequest req, HttpServletResponse res, PageAction get, PageAction post)
            throws IOException {
        WebSession session = (WebSession) req.getAttribute(WebSurface.SESSION_ATTRIBUTE);
        if (session == null) {
            res.setStatus(HttpServletResponse.SC_FOUND);
            res.setHeader("Location", WebSessionFilter.LOGIN_PATH);
            return;
        }
        if ("GET".equals(req.getMethod()) || "HEAD".equals(req.getMethod())) {
            get.run(session);
        } else if ("POST".equals(req.getMethod())) {
            post.run(session);
        } else {
            methodNotAllowed(res);
        }
    }

    private static void methodNotAllowed(HttpServletResponse res) throws IOException {
        res.setHeader("Allow", "GET");
        writePage(res, HttpServletResponse.SC_METHOD_NOT_ALLOWED, "Method not allowed", "<p>Method not allowed.</p>");
    }

    // ---- sign-in ----

    private void login(HttpServletRequest req, HttpServletResponse res) throws IOException {
        URI authorization;
        try {
            authorization = provider.authorizationEndpoint();
        } catch (OidcException e) {
            writePage(res, HttpServletResponse.SC_SERVICE_UNAVAILABLE, "Sign-in unavailable",
                    "<p>Sign-in is not available right now. Try again later.</p>");
            return;
        }
        String verifier = randomToken();
        String nonce = randomToken();
        String state;
        try {
            state = pending.begin(nonce, verifier);
        } catch (OidcPendingStore.LimitReachedException e) {
            writePage(res, HttpServletResponse.SC_SERVICE_UNAVAILABLE, "Sign-in unavailable",
                    "<p>Too many sign-ins are in progress. Try again in a few minutes.</p>");
            return;
        }
        StringBuilder url = new StringBuilder(authorization.toString()).append(authorization.getRawQuery() == null
                ? "?" : "&");
        url.append("response_type=code&client_id=").append(enc(config.clientId()))
                .append("&redirect_uri=").append(enc(config.redirectUri())).append("&scope=openid")
                .append("&state=").append(enc(state)).append("&nonce=").append(enc(nonce))
                .append("&code_challenge=").append(enc(challenge(verifier))).append("&code_challenge_method=S256");
        if ("1".equals(req.getParameter("reauth"))) {
            url.append("&prompt=login&max_age=0");
        }
        // Lax, not Strict: the browser comes back from the IdP in a cross-site navigation, which must carry it.
        res.addHeader("Set-Cookie", BINDING_COOKIE + "=" + state + "; Path=/; Secure; HttpOnly; SameSite=Lax; Max-Age="
                + OidcPendingStore.TTL.toSeconds());
        res.setStatus(HttpServletResponse.SC_FOUND);
        res.setHeader("Location", url.toString());
    }

    private void callback(HttpServletRequest req, HttpServletResponse res) throws IOException {
        String state = bounded(req.getParameter("state"));
        clearBindingCookie(res);
        if (state == null) {
            badRequest(res);
            return;
        }
        // The state is burned first, whatever else is wrong with the request.
        Optional<OidcPendingStore.Pending> login = pending.consume(state);
        String binding = WebSessionFilter.cookie(req, BINDING_COOKIE);
        if (login.isEmpty() || binding == null || !MessageDigest.isEqual(binding.getBytes(StandardCharsets.UTF_8),
                state.getBytes(StandardCharsets.UTF_8))) {
            LOG.debug("login callback refused: state");
            badRequest(res);
            return;
        }
        String code = bounded(req.getParameter("code"));
        if (req.getParameter("error") != null || code == null) {
            LOG.debug("login callback refused: no code");
            badRequest(res);
            return;
        }
        IdToken token;
        try {
            token = provider.exchange(code, login.get().pkceVerifier(), login.get().nonce());
        } catch (OidcException e) {
            if (OidcException.INVALID_TOKEN.equals(e.kind())) {
                badRequest(res);
            } else {
                writePage(res, HttpServletResponse.SC_BAD_GATEWAY, "Sign-in failed",
                        "<p>The identity provider did not complete the sign-in. Try again later.</p>");
            }
            return;
        }
        TenantProvisioner.TenantRef tenant;
        try {
            tenant = tenantFor(token);
        } catch (AuditWriteException e) { // fail-closed (D5): no audit row, no session
            writePage(res, HttpServletResponse.SC_SERVICE_UNAVAILABLE, "Sign-in unavailable",
                    "<p>Sign-in is not available right now. Try again later.</p>");
            return;
        }
        if (tenant == null || tenant.disabled()) {
            LOG.debug("login refused: tenant");
            writePage(res, HttpServletResponse.SC_FORBIDDEN, "No access",
                    "<p>This account is not allowed to use this service.</p>");
            return;
        }
        // A missing or future auth_time is never "fresh": the epoch makes every freshness check fail.
        Instant authTime = token.authTime() != null && !token.authTime().isAfter(clock.instant().plusSeconds(60))
                ? token.authTime() : Instant.EPOCH;
        WebSessionStore.Created session = sessions.create(tenant.tenantId(), authTime);
        res.addHeader("Set-Cookie", WebSessionFilter.COOKIE + "=" + session.sessionId()
                + "; Path=/; Secure; HttpOnly; SameSite=Strict; Max-Age=" + WebSessionStore.ABSOLUTE_TIMEOUT.toSeconds());
        // A page that moves on by itself: the redirect chain began at the IdP, a cross-site start that a Strict
        // cookie would not survive, while a navigation from our own page is same-site.
        writePage(res, HttpServletResponse.SC_OK, "Signed in",
                "<meta http-equiv=\"refresh\" content=\"0;url=" + WebConfig.BASE_PATH + "\">",
                "<p>Signed in. <a href=\"" + WebConfig.BASE_PATH + "\">Continue</a></p>");
    }

    /** Existing tenant, or a new one only when the role allows it (D2); null when there is none. */
    private TenantProvisioner.TenantRef tenantFor(IdToken token) {
        Optional<TenantProvisioner.TenantRef> existing = tenants.find(token.issuer(), token.subject());
        if (existing.isPresent()) {
            return existing.get();
        }
        boolean allowed = config.requiredRole() == null
                ? config.allowWithoutRole()
                : Roles.has(token.claims(), config.roleClaim(), config.requiredRole());
        if (!allowed) {
            return null;
        }
        // the provisioner writes the tenant_created row in the same transaction as the tenant
        return tenants.create(token.issuer(), token.subject(), UUID.randomUUID().toString());
    }

    private static void clearBindingCookie(HttpServletResponse res) {
        res.addHeader("Set-Cookie", BINDING_COOKIE + "=; Path=/; Secure; HttpOnly; SameSite=Lax; Max-Age=0");
    }

    private static void badRequest(HttpServletResponse res) throws IOException {
        writePage(res, HttpServletResponse.SC_BAD_REQUEST, "Sign-in failed",
                "<p>The sign-in could not be completed. <a href=\"" + WebSessionFilter.LOGIN_PATH
                        + "\">Start again</a></p>");
    }

    // ---- signed-in pages ----

    private void home(HttpServletRequest req, HttpServletResponse res) throws IOException {
        WebSession session = (WebSession) req.getAttribute(WebSurface.SESSION_ATTRIBUTE);
        if (session == null) { // reached without the session filter: never serve a page
            res.setStatus(HttpServletResponse.SC_FOUND);
            res.setHeader("Location", WebSessionFilter.LOGIN_PATH);
            return;
        }
        pages.list(session, res);
    }

    private void logout(HttpServletRequest req, HttpServletResponse res) throws IOException {
        String id = WebSessionFilter.cookie(req, WebSessionFilter.COOKIE);
        if (id != null) {
            sessions.delete(id);
        }
        res.addHeader("Set-Cookie", WebSessionFilter.COOKIE + "=; Path=/; Secure; HttpOnly; SameSite=Strict; Max-Age=0");
        writePage(res, HttpServletResponse.SC_OK, "Signed out",
                "<p>You are signed out. <a href=\"" + WebSessionFilter.LOGIN_PATH + "\">Sign in again</a></p>");
    }

    /** The sign-out form with the session's CSRF token; the token is base64url, so it needs no escaping, but gets it. */
    static String logoutForm(WebSession session) {
        return "<form action=\"" + WebConfig.BASE_PATH + "/logout\" method=\"post\"><input type=\"hidden\" name=\""
                + WebSessionFilter.CSRF_FIELD + "\" value=\"" + Html.escape(session.csrfToken())
                + "\"><button type=\"submit\">Sign out</button></form>";
    }

    // ---- helpers ----

    static void writePage(HttpServletResponse res, int status, String title, String body) throws IOException {
        writePage(res, status, title, "", body);
    }

    static void writePage(HttpServletResponse res, int status, String title, String head, String body)
            throws IOException {
        WebSecurityFilter.applyHeaders(res);
        byte[] bytes = Html.page(title, head, body).getBytes(StandardCharsets.UTF_8);
        res.setStatus(status);
        res.setContentType("text/html;charset=utf-8");
        res.setContentLength(bytes.length);
        res.getOutputStream().write(bytes);
    }

    private static String bounded(String value) {
        return value == null || value.isEmpty() || value.length() > MAX_PARAM ? null : value;
    }

    private String randomToken() {
        byte[] b = new byte[32];
        random.nextBytes(b);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(b);
    }

    private static String challenge(String verifier) {
        try {
            return Base64.getUrlEncoder().withoutPadding().encodeToString(
                    MessageDigest.getInstance("SHA-256").digest(verifier.getBytes(StandardCharsets.US_ASCII)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String enc(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8);
    }
}
