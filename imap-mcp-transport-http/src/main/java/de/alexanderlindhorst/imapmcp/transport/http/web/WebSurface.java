package de.alexanderlindhorst.imapmcp.transport.http.web;

import de.alexanderlindhorst.imapmcp.core.linking.AccountLinker;
import de.alexanderlindhorst.imapmcp.core.web.OidcPendingStore;
import de.alexanderlindhorst.imapmcp.core.web.TenantProvisioner;
import de.alexanderlindhorst.imapmcp.core.web.WebSessionStore;
import de.alexanderlindhorst.imapmcp.transport.http.KeysUnavailableReporter;
import jakarta.servlet.Filter;
import jakarta.servlet.http.HttpServlet;
import java.time.Clock;
import java.util.List;

/**
 * The linking surface mounted at {@code /accounts} (SPEC 5.3): its filters in order (security headers and request
 * guards, then the session and CSRF check) and the servlet. No bearer token and none of the {@code /mcp} filters.
 */
public record WebSurface(List<Filter> filters, HttpServlet servlet) {

    /** Request attribute holding the {@link de.alexanderlindhorst.imapmcp.core.web.WebSession} of a checked request. */
    public static final String SESSION_ATTRIBUTE = WebSurface.class.getName() + ".session";

    public WebSurface {
        filters = List.copyOf(filters);
    }

    public static WebSurface of(WebConfig config, OidcProvider provider, OidcPendingStore pending,
            WebSessionStore sessions, TenantProvisioner tenants, AccountLinker linker, Clock clock) {
        return of(config, provider, pending, sessions, tenants, linker, clock, KeysUnavailableReporter.withoutMetrics());
    }

    /** With the reporter of key unavailability, shared with the {@code /mcp} token filter (SPEC 5.3, D16). */
    public static WebSurface of(WebConfig config, OidcProvider provider, OidcPendingStore pending,
            WebSessionStore sessions, TenantProvisioner tenants, AccountLinker linker, Clock clock,
            KeysUnavailableReporter keysUnavailable) {
        return new WebSurface(List.of(new WebSecurityFilter(config), new WebSessionFilter(sessions)),
                new AccountsServlet(config, provider, pending, sessions, tenants, linker, clock, keysUnavailable));
    }
}
