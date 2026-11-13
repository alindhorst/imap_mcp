package de.alexanderlindhorst.imapmcp.transport.http.web;

import de.alexanderlindhorst.imapmcp.core.account.AccountHandles;
import de.alexanderlindhorst.imapmcp.core.account.MailAccount;
import de.alexanderlindhorst.imapmcp.core.account.ResolvedAccount;
import de.alexanderlindhorst.imapmcp.core.linking.AccountLinker;
import de.alexanderlindhorst.imapmcp.core.linking.LinkOutcome;
import de.alexanderlindhorst.imapmcp.core.linking.LinkRequest;
import de.alexanderlindhorst.imapmcp.core.linking.SettingsRequest;
import de.alexanderlindhorst.imapmcp.core.linking.UpstreamLinker;
import de.alexanderlindhorst.imapmcp.core.linking.UpstreamProviderInfo;
import de.alexanderlindhorst.imapmcp.core.web.WebSession;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * The account pages of the linking surface (SPEC 5.3, D4): list, link, re-link, settings and unlink, server-rendered
 * without JavaScript. The tenant is always the one of the web session; the only account reference a form carries is
 * the {@code acc_} handle, looked up within that tenant. Passwords arrive as form fields, go to the linker as a
 * {@code char[]} that is wiped afterwards, and are never echoed, logged or put in a URL. Messages are fixed texts.
 */
final class LinkingPages {

    private static final String ACCOUNTS = WebConfig.BASE_PATH;
    private static final String READ_ONLY = "read_only";
    private static final String READ_WRITE = "read_write";

    private final AccountLinker linker;
    private final UpstreamLinker upstream;

    LinkingPages(AccountLinker linker, UpstreamLinker upstream) {
        this.linker = linker;
        this.upstream = upstream;
    }

    // ---- list ----

    void list(WebSession session, HttpServletResponse res) throws IOException {
        List<ResolvedAccount> accounts = linker.list(session.tenantId());
        StringBuilder body = new StringBuilder("<h1>Mail accounts</h1>");
        if (accounts.isEmpty()) {
            body.append("<p>No mail account is linked yet.</p>");
        }
        for (ResolvedAccount r : accounts) {
            MailAccount a = r.account();
            String h = Html.escape(r.handle());
            boolean oauth = a.auth() == MailAccount.Auth.XOAUTH2;
            body.append("<section><h2>").append(Html.escape(a.label())).append("</h2><p>")
                    .append(Html.escape(a.username())).append(" at ").append(Html.escape(a.host())).append(':')
                    .append(a.port()).append("<br>")
                    .append(a.status() == MailAccount.Status.NEEDS_RELINK
                            ? (oauth ? "This account needs to be authorized again." : "This account needs a new password.")
                            : "Active.")
                    .append(' ').append(a.readOnly() ? "Read-only." : "Read and write.")
                    .append(a.allowPurge() ? " Permanent deletion allowed." : "").append("</p>")
                    .append("<p><a href=\"");
            if (oauth) { // the provider and host are fixed: the same sign-in again, never a password
                body.append(ACCOUNTS).append("/oauth/").append(Html.escape(String.valueOf(a.providerId())))
                        .append("/start?account=").append(h).append("\">Authorize again</a>");
            } else {
                body.append(ACCOUNTS).append("/relink?account=").append(h).append("\">New password</a>");
            }
            body.append(" <a href=\"").append(ACCOUNTS).append("/settings?account=")
                    .append(h).append("\">Settings</a> <a href=\"").append(ACCOUNTS).append("/unlink?account=")
                    .append(h).append("\">Unlink</a></p></section>");
        }
        if (accounts.size() >= linker.maxAccounts()) {
            body.append("<p>The limit of ").append(linker.maxAccounts())
                    .append(" accounts is reached. Unlink one to add another.</p>");
        } else {
            body.append("<p><a href=\"").append(ACCOUNTS).append("/link\">Add a mail account</a></p>");
            if (upstream != null) {
                for (UpstreamProviderInfo p : upstream.providers()) {
                    body.append("<p><a href=\"").append(ACCOUNTS).append("/oauth/").append(Html.escape(p.id()))
                            .append("/start\">Add a ").append(Html.escape(p.label())).append(" account</a></p>");
                }
            }
        }
        body.append(AccountsServlet.logoutForm(session));
        AccountsServlet.writePage(res, HttpServletResponse.SC_OK, "Mail accounts", body.toString());
    }

    // ---- link ----

    void linkForm(WebSession session, HttpServletResponse res) throws IOException {
        StringBuilder f = new StringBuilder("<h1>Add a mail account</h1>");
        f.append("<form action=\"").append(ACCOUNTS).append("/link\" method=\"post\">").append(csrf(session))
                .append(field("label", "Name", "text", "", "off"))
                .append(field("host", "IMAP server", "text", "", "off"))
                .append(field("port", "Port (993 with TLS, 143 with STARTTLS)", "text", "993", "off"))
                .append("<p><label>Connection security <select name=\"security\">"
                        + "<option value=\"IMPLICIT_TLS\" selected>TLS (port 993)</option>"
                        + "<option value=\"STARTTLS\">STARTTLS (port 143)</option></select></label></p>")
                .append(field("username", "User name", "text", "", "off"))
                .append("<p><label>Password <input type=\"password\" name=\"password\" "
                        + "autocomplete=\"new-password\"></label></p>")
                .append(accessSelect(true, true));
        if (linker.purgeEnabled()) {
            f.append(purgeBox(false));
        }
        f.append("<p><button type=\"submit\">Check and add</button></p></form><p><a href=\"").append(ACCOUNTS)
                .append("\">Back</a></p>");
        AccountsServlet.writePage(res, HttpServletResponse.SC_OK, "Add a mail account", f.toString());
    }

    void linkSubmit(WebSession session, HttpServletRequest req, HttpServletResponse res) throws IOException {
        int port;
        try {
            port = Integer.parseInt(param(req, "port").strip());
        } catch (NumberFormatException e) {
            port = -1;
        }
        MailAccount.Security security = switch (param(req, "security")) {
            case "IMPLICIT_TLS" -> MailAccount.Security.IMPLICIT_TLS;
            case "STARTTLS" -> MailAccount.Security.STARTTLS;
            default -> null;
        };
        if (port < 0 || security == null) { // not a number / not an offered choice: no need to ask the linker
            outcome(res, LinkOutcome.INVALID_INPUT, "");
            return;
        }
        LinkRequest request = new LinkRequest(param(req, "label"), param(req, "host"), port, security,
                param(req, "username"), readOnly(req), req.getParameter("allow_purge") != null);
        char[] password = param(req, "password").toCharArray();
        try {
            LinkOutcome o = linker.link(session.tenantId(), request, password, session.authTime(), requestId());
            outcome(res, o, "The account was added.");
        } finally {
            Arrays.fill(password, '\0');
        }
    }

    // ---- re-link ----

    void relinkForm(WebSession session, HttpServletRequest req, HttpServletResponse res) throws IOException {
        Optional<ResolvedAccount> account = account(session, req)
                .filter(a -> a.account().auth() == MailAccount.Auth.PASSWORD); // upstream accounts: sign in again
        if (account.isEmpty()) {
            notFound(res);
            return;
        }
        String body = "<h1>New password</h1><p>Account: " + Html.escape(account.get().account().label())
                + "</p><p>Enter the current password of this mailbox. Server and user name cannot be changed here;"
                + " unlink and add the account again for that.</p><form action=\"" + ACCOUNTS
                + "/relink\" method=\"post\">" + csrf(session) + handleInput(account.get())
                + "<p><label>Password <input type=\"password\" name=\"password\" autocomplete=\"new-password\">"
                + "</label></p><p><button type=\"submit\">Check and save</button></p></form><p><a href=\"" + ACCOUNTS
                + "\">Back</a></p>";
        AccountsServlet.writePage(res, HttpServletResponse.SC_OK, "New password", body);
    }

    void relinkSubmit(WebSession session, HttpServletRequest req, HttpServletResponse res) throws IOException {
        char[] password = param(req, "password").toCharArray();
        try {
            outcome(res, linker.relink(session.tenantId(), handle(req), password, requestId()),
                    "The new password was saved.");
        } finally {
            Arrays.fill(password, '\0');
        }
    }

    // ---- settings ----

    void settingsForm(WebSession session, HttpServletRequest req, HttpServletResponse res) throws IOException {
        Optional<ResolvedAccount> account = account(session, req);
        if (account.isEmpty()) {
            notFound(res);
            return;
        }
        MailAccount a = account.get().account();
        StringBuilder f = new StringBuilder("<h1>Settings</h1><form action=\"").append(ACCOUNTS)
                .append("/settings\" method=\"post\">").append(csrf(session)).append(handleInput(account.get()))
                .append(field("label", "Name", "text", a.label(), "off")).append(accessSelect(a.readOnly(), false));
        if (linker.purgeEnabled() || a.allowPurge()) {
            f.append(purgeBox(a.allowPurge()));
        }
        f.append("<p><button type=\"submit\">Save</button></p></form><p><a href=\"").append(ACCOUNTS)
                .append("\">Back</a></p>");
        AccountsServlet.writePage(res, HttpServletResponse.SC_OK, "Settings", f.toString());
    }

    void settingsSubmit(WebSession session, HttpServletRequest req, HttpServletResponse res) throws IOException {
        SettingsRequest request = new SettingsRequest(param(req, "label"), readOnly(req),
                req.getParameter("allow_purge") != null);
        outcome(res, linker.updateSettings(session.tenantId(), handle(req), request, session.authTime(),
                requestId()), "The settings were saved.");
    }

    // ---- unlink ----

    void unlinkForm(WebSession session, HttpServletRequest req, HttpServletResponse res) throws IOException {
        Optional<ResolvedAccount> account = account(session, req);
        if (account.isEmpty()) {
            notFound(res);
            return;
        }
        String body = "<h1>Unlink</h1><p>Remove the account " + Html.escape(account.get().account().label())
                + " and its stored password? Its handles stop working at once.</p><form action=\"" + ACCOUNTS
                + "/unlink\" method=\"post\">" + csrf(session) + handleInput(account.get())
                + "<p><button type=\"submit\">Unlink</button></p></form><p><a href=\"" + ACCOUNTS
                + "\">Cancel</a></p>";
        AccountsServlet.writePage(res, HttpServletResponse.SC_OK, "Unlink", body);
    }

    void unlinkSubmit(WebSession session, HttpServletRequest req, HttpServletResponse res) throws IOException {
        outcome(res, linker.unlink(session.tenantId(), handle(req), requestId()), "The account was unlinked.");
    }

    // ---- helpers ----

    private void outcome(HttpServletResponse res, LinkOutcome o, String success) throws IOException {
        int status;
        String message;
        switch (o) {
            case OK -> { // post/redirect/get: a reload of the result page cannot repeat the action
                res.setStatus(HttpServletResponse.SC_SEE_OTHER);
                res.setHeader("Location", ACCOUNTS);
                res.setContentLength(0);
                return;
            }
            case INVALID_INPUT -> {
                status = HttpServletResponse.SC_BAD_REQUEST;
                message = "Please check the fields and try again.";
            }
            case HOST_REFUSED -> {
                status = HttpServletResponse.SC_BAD_REQUEST;
                message = "This mail server cannot be used with this service.";
            }
            case RATE_LIMITED -> {
                status = 429;
                message = "There were too many connection checks. Try again in an hour.";
            }
            case PROBE_AUTH_FAILED -> {
                status = HttpServletResponse.SC_BAD_REQUEST;
                message = "The mail server did not accept the user name or password.";
            }
            case PROBE_UNREACHABLE -> {
                status = HttpServletResponse.SC_BAD_GATEWAY;
                message = "The service could not connect to the mail server.";
            }
            case ACCOUNT_LIMIT -> {
                status = HttpServletResponse.SC_CONFLICT;
                message = "The limit of accounts is reached.";
            }
            case NOT_FOUND -> {
                notFound(res);
                return;
            }
            case REAUTH_REQUIRED -> {
                AccountsServlet.writePage(res, HttpServletResponse.SC_FORBIDDEN, "Sign in again",
                        "<p>For this change you must have signed in within the last 5 minutes. "
                                + "<a href=\"" + ACCOUNTS + "/login?reauth=1\">Sign in again</a></p>");
                return;
            }
            case PURGE_DISABLED -> {
                status = HttpServletResponse.SC_FORBIDDEN;
                message = "Permanent deletion is not available on this service.";
            }
            default -> {
                status = HttpServletResponse.SC_SERVICE_UNAVAILABLE;
                message = "This did not work right now. Nothing was changed; try again later.";
            }
        }
        AccountsServlet.writePage(res, status, "Not done",
                "<p>" + Html.escape(message) + "</p><p><a href=\"" + ACCOUNTS + "\">Back to your accounts</a></p>");
    }

    static void notFound(HttpServletResponse res) throws IOException {
        AccountsServlet.writePage(res, HttpServletResponse.SC_NOT_FOUND, "Not found",
                "<p>Account not found.</p><p><a href=\"" + ACCOUNTS + "\">Back to your accounts</a></p>");
    }

    private Optional<ResolvedAccount> account(WebSession session, HttpServletRequest req) {
        String h = handle(req);
        return h == null ? Optional.empty() : linker.find(session.tenantId(), h);
    }

    /** The {@code account} parameter if it has the shape of a handle, else null (it never reaches a lookup). */
    private static String handle(HttpServletRequest req) {
        String h = req.getParameter("account");
        return AccountHandles.isValid(h) ? h : null;
    }

    private static String param(HttpServletRequest req, String name) {
        String v = req.getParameter(name);
        return v == null ? "" : v;
    }

    /** Anything but an explicit {@code read_write} is read-only. */
    private static boolean readOnly(HttpServletRequest req) {
        return !READ_WRITE.equals(req.getParameter("access"));
    }

    private static String requestId() {
        return UUID.randomUUID().toString();
    }

    private static String csrf(WebSession session) {
        return "<input type=\"hidden\" name=\"" + WebSessionFilter.CSRF_FIELD + "\" value=\""
                + Html.escape(session.csrfToken()) + "\">";
    }

    private static String handleInput(ResolvedAccount r) {
        return "<input type=\"hidden\" name=\"account\" value=\"" + Html.escape(r.handle()) + "\">";
    }

    private static String field(String name, String label, String type, String value, String autocomplete) {
        return "<p><label>" + Html.escape(label) + " <input type=\"" + type + "\" name=\"" + name + "\" value=\""
                + Html.escape(value) + "\" autocomplete=\"" + autocomplete + "\"></label></p>";
    }

    private static String accessSelect(boolean readOnly, boolean isNew) {
        return "<p><label>Access <select name=\"access\"><option value=\"" + READ_ONLY + "\""
                + (readOnly ? " selected" : "") + ">Read-only (recommended)</option><option value=\"" + READ_WRITE
                + "\"" + (readOnly ? "" : " selected") + ">Read and write</option></select></label></p>";
    }

    private static String purgeBox(boolean checked) {
        return "<p><label><input type=\"checkbox\" name=\"allow_purge\"" + (checked ? " checked" : "")
                + "> Allow permanent deletion from the Trash folder (needs a sign-in from the last 5 minutes)"
                + "</label></p>";
    }
}
