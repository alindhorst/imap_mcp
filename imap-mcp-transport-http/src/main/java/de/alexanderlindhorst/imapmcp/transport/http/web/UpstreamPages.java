package de.alexanderlindhorst.imapmcp.transport.http.web;

import de.alexanderlindhorst.imapmcp.core.account.AccountHandles;
import de.alexanderlindhorst.imapmcp.core.account.MailAccount;
import de.alexanderlindhorst.imapmcp.core.account.ResolvedAccount;
import de.alexanderlindhorst.imapmcp.core.linking.AccountLinker;
import de.alexanderlindhorst.imapmcp.core.linking.UpstreamLinker;
import de.alexanderlindhorst.imapmcp.core.linking.UpstreamOutcome;
import de.alexanderlindhorst.imapmcp.core.linking.UpstreamPendingStore;
import de.alexanderlindhorst.imapmcp.core.linking.UpstreamProviderInfo;
import de.alexanderlindhorst.imapmcp.core.web.WebSession;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The upstream OAuth part of the linking surface (SPEC 5.3, v0.29, D4): the start form, the start (a {@code 303} to
 * the provider) and the callback pages. The tenant of a start is the web session's; the callback has no session
 * (a {@code SameSite=Strict} cookie is not sent on the return from the provider) and takes the tenant from the
 * pending row, bound to the browser by the cookie {@value #COOKIE}. Every outcome is a fixed page; neither
 * {@code error_description}, nor a code, state, token or address is ever shown or logged.
 */
final class UpstreamPages {

    static final String COOKIE = "__Host-upstream";
    static final String PREFIX = WebConfig.BASE_PATH + "/oauth/";
    private static final Pattern ROUTE = Pattern.compile("/accounts/oauth/([a-z0-9-]{1,32})/(start|callback)");
    private static final int MAX_PARAM = 2048;
    private static final String ACCOUNTS = WebConfig.BASE_PATH;
    private static final String BACK = "<p><a href=\"" + ACCOUNTS + "\">Back to your accounts</a></p>";

    private final UpstreamLinker upstream;
    private final AccountLinker linker;

    UpstreamPages(UpstreamLinker upstream, AccountLinker linker) {
        this.upstream = upstream;
        this.linker = linker;
    }

    /** {@code /accounts/oauth/<id>/callback}: the one path of the surface that is served without a session. */
    static boolean isCallbackPath(String path) {
        Matcher m = ROUTE.matcher(path);
        return m.matches() && "callback".equals(m.group(2));
    }

    /** The provider id of a {@code start} or {@code callback} path, or null for any other path. */
    static String providerId(String path) {
        Matcher m = ROUTE.matcher(path);
        return m.matches() ? m.group(1) : null;
    }

    static boolean isStartPath(String path) {
        Matcher m = ROUTE.matcher(path);
        return m.matches() && "start".equals(m.group(2));
    }

    // ---- start ----

    void startForm(WebSession session, HttpServletRequest req, HttpServletResponse res, String providerId)
            throws IOException {
        Optional<UpstreamProviderInfo> provider = upstream.provider(providerId);
        String handle = handleParam(req);
        if (provider.isEmpty() || (req.getParameter("account") != null && handle == null)) {
            LinkingPages.notFound(res);
            return;
        }
        String action = PREFIX + providerId + "/start";
        StringBuilder f = new StringBuilder();
        if (handle != null) {
            Optional<ResolvedAccount> account = linker.find(session.tenantId(), handle)
                    .filter(a -> a.account().auth() == MailAccount.Auth.XOAUTH2
                            && providerId.equals(a.account().providerId()));
            if (account.isEmpty()) {
                LinkingPages.notFound(res);
                return;
            }
            f.append("<h1>Authorize again</h1><p>Account: ").append(Html.escape(account.get().account().label()))
                    .append("</p><p>You will be sent to ").append(Html.escape(provider.get().label()))
                    .append(" to allow access again. Server and address cannot be changed here; unlink and add the "
                            + "account again for that.</p><form action=\"").append(action)
                    .append("\" method=\"post\">").append(csrf(session))
                    .append("<input type=\"hidden\" name=\"account\" value=\"")
                    .append(Html.escape(account.get().handle())).append("\">")
                    .append("<p><button type=\"submit\">Continue to ").append(Html.escape(provider.get().label()))
                    .append("</button></p></form>");
        } else {
            f.append("<h1>Add a ").append(Html.escape(provider.get().label())).append(" account</h1>")
                    .append("<p>You will be sent to ").append(Html.escape(provider.get().label()))
                    .append(" to allow access. The address below is only a hint for the sign-in there; "
                            + "the connection is checked before anything is saved.</p><form action=\"")
                    .append(action).append("\" method=\"post\">").append(csrf(session))
                    .append("<p><label>Name <input type=\"text\" name=\"label\" value=\"\" autocomplete=\"off\">"
                            + "</label></p>")
                    .append("<p><label>Mail address <input type=\"email\" name=\"address\" value=\"\" "
                            + "autocomplete=\"email\"></label></p>")
                    .append("<p><label>Access <select name=\"access\"><option value=\"read_only\" selected>"
                            + "Read-only (recommended)</option><option value=\"read_write\">Read and write</option>"
                            + "</select></label></p>")
                    .append("<p><button type=\"submit\">Continue to ").append(Html.escape(provider.get().label()))
                    .append("</button></p></form>");
        }
        f.append("<p><a href=\"").append(ACCOUNTS).append("\">Back</a></p>");
        // Chrome applies form-action to the redirect that answers the POST, so the provider's origin must be allowed
        writePage(res, HttpServletResponse.SC_OK, "Add a mail account", f.toString(),
                WebSecurityFilter.csp(originOf(provider.get().authorizationEndpoint())));
    }

    void startSubmit(WebSession session, HttpServletRequest req, HttpServletResponse res, String providerId)
            throws IOException {
        String account = req.getParameter("account");
        String handle = handleParam(req);
        if (account != null && handle == null) {
            LinkingPages.notFound(res);
            return;
        }
        UpstreamLinker.Started s = upstream.start(session.tenantId(), providerId, param(req, "label"),
                param(req, "address"), !"read_write".equals(req.getParameter("access")), handle,
                UUID.randomUUID().toString());
        if (s.outcome() != UpstreamOutcome.OK) {
            refused(res, s.outcome());
            return;
        }
        // Lax, not Strict: the browser comes back from the provider in a cross-site navigation, which must carry it.
        res.addHeader("Set-Cookie", COOKIE + "=" + s.state() + "; Path=/; Secure; HttpOnly; SameSite=Lax; Max-Age="
                + UpstreamPendingStore.TTL.toSeconds());
        res.setStatus(HttpServletResponse.SC_SEE_OTHER);
        res.setHeader("Location", s.location());
        res.setContentLength(0);
    }

    private static void refused(HttpServletResponse res, UpstreamOutcome o) throws IOException {
        int status;
        String message;
        switch (o) {
            case NOT_FOUND -> {
                LinkingPages.notFound(res);
                return;
            }
            case INVALID_INPUT -> {
                status = HttpServletResponse.SC_BAD_REQUEST;
                message = "Please check the fields and try again.";
            }
            case ACCOUNT_LIMIT -> {
                status = HttpServletResponse.SC_CONFLICT;
                message = "The limit of accounts is reached.";
            }
            case RATE_LIMITED -> {
                status = 429;
                message = "There were too many connection checks. Try again in an hour.";
            }
            case START_LIMIT -> {
                status = 429;
                message = "Too many sign-ins were started. Try again in an hour.";
            }
            case PENDING_LIMIT -> {
                status = 429;
                message = "Other sign-ins are still in progress. Finish them or try again in 10 minutes.";
            }
            default -> {
                status = HttpServletResponse.SC_SERVICE_UNAVAILABLE;
                message = "This did not work right now. Nothing was changed; try again later.";
            }
        }
        AccountsServlet.writePage(res, status, "Not done", "<p>" + Html.escape(message) + "</p>" + BACK);
    }

    // ---- callback ----

    void callback(HttpServletRequest req, HttpServletResponse res, String providerId) throws IOException {
        // the cookie is single-use like the state: cleared whatever the outcome
        res.addHeader("Set-Cookie", COOKIE + "=; Path=/; Secure; HttpOnly; SameSite=Lax; Max-Age=0");
        if (upstream.provider(providerId).isEmpty()) {
            LinkingPages.notFound(res);
            return;
        }
        String error = req.getParameter("error");
        UpstreamLinker.Completed c = upstream.callback(bounded(req.getParameter("state")),
                WebSessionFilter.cookie(req, COOKIE), providerId, bounded(req.getParameter("code")),
                error == null ? null : (bounded(error) == null ? "invalid" : error));
        switch (c.outcome()) {
            case OK -> AccountsServlet.writePage(res, HttpServletResponse.SC_OK, "Account linked",
                    "<meta http-equiv=\"refresh\" content=\"0;url=" + ACCOUNTS + "\">",
                    "<p>" + (c.relink() ? "The account was linked again." : "The account was linked.")
                            + " <a href=\"" + ACCOUNTS + "\">Continue</a></p>");
            case EXPIRED -> failure(res, HttpServletResponse.SC_BAD_REQUEST, "Request expired",
                    "Not linked: request expired, start again.");
            case DENIED -> failure(res, HttpServletResponse.SC_BAD_REQUEST, "Not linked",
                    "Not linked: access was not granted.");
            case PROVIDER_ERROR -> failure(res, HttpServletResponse.SC_BAD_GATEWAY, "Not linked",
                    "Not linked: provider refused the request.");
            case PROVIDER_UNAVAILABLE -> failure(res, HttpServletResponse.SC_SERVICE_UNAVAILABLE, "Not linked",
                    "Not linked: provider unavailable, try later.");
            case INCOMPLETE -> failure(res, HttpServletResponse.SC_BAD_REQUEST, "Not linked",
                    "Not linked: authorization incomplete, start again.");
            case PROBE_FAILED -> failure(res, HttpServletResponse.SC_BAD_REQUEST, "Not linked",
                    "Not linked: the account could not be verified with the mail server.");
            case DUPLICATE -> failure(res, HttpServletResponse.SC_CONFLICT, "Not linked",
                    "Not linked: already linked; use re-link.");
            case LIMIT, ACCOUNT_LIMIT -> failure(res, HttpServletResponse.SC_CONFLICT, "Not linked",
                    "Not linked: the limit of accounts is reached.");
            case TENANT_DISABLED -> failure(res, HttpServletResponse.SC_FORBIDDEN, "Not linked",
                    "Not linked: account linking is not available.");
            default -> failure(res, HttpServletResponse.SC_SERVICE_UNAVAILABLE, "Not linked",
                    "This did not work right now. Nothing was changed; try again later.");
        }
    }

    private static void failure(HttpServletResponse res, int status, String title, String message)
            throws IOException {
        AccountsServlet.writePage(res, status, title, "<p>" + Html.escape(message) + "</p>" + BACK);
    }

    // ---- helpers ----

    private static void writePage(HttpServletResponse res, int status, String title, String body, String csp)
            throws IOException {
        WebSecurityFilter.applyHeaders(res);
        res.setHeader("Content-Security-Policy", csp);
        byte[] bytes = Html.page(title, "", body).getBytes(StandardCharsets.UTF_8);
        res.setStatus(status);
        res.setContentType("text/html;charset=utf-8");
        res.setContentLength(bytes.length);
        res.getOutputStream().write(bytes);
    }

    /** {@code scheme://host[:port]} of an https endpoint, for the CSP source list. */
    private static String originOf(URI endpoint) {
        return endpoint.getScheme() + "://" + endpoint.getRawAuthority();
    }

    private static String handleParam(HttpServletRequest req) {
        String h = req.getParameter("account");
        return AccountHandles.isValid(h) ? h : null;
    }

    private static String param(HttpServletRequest req, String name) {
        String v = req.getParameter(name);
        return v == null ? "" : v;
    }

    private static String bounded(String value) {
        return value == null || value.isEmpty() || value.length() > MAX_PARAM ? null : value;
    }

    private static String csrf(WebSession session) {
        return "<input type=\"hidden\" name=\"" + WebSessionFilter.CSRF_FIELD + "\" value=\""
                + Html.escape(session.csrfToken()) + "\">";
    }
}
