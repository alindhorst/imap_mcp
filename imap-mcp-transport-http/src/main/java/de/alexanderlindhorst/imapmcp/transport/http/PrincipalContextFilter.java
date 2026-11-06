package de.alexanderlindhorst.imapmcp.transport.http;

import de.alexanderlindhorst.imapmcp.core.tenancy.AccountContext;
import de.alexanderlindhorst.imapmcp.core.tenancy.TenantId;
import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Maps the principal validated by {@link JwtTokenFilter} to the request's {@link AccountContext} (SPEC 5.3, D2,
 * D3). Order: the read scope {@code imap-mcp:read} first (missing: {@code 403} with
 * {@code WWW-Authenticate: Bearer error="insufficient_scope", scope="imap-mcp:read"}, no lookup), then the tenant
 * row by (issuer, subject claim): a disabled tenant gets {@code 403}; a principal without a row gets the context of
 * {@link TenantId#NONE}, which owns nothing (so {@code list_accounts} is empty and every other tool says
 * {@code ACCOUNT_NOT_FOUND}); no MCP request creates a row. The token's scopes travel in the context for the
 * per-call write-scope check. Nothing from the request other than the validated token selects the tenant, and
 * neither token nor identifiers are logged. A failing lookup, or a row with the reserved nil id, is {@code 503} (fail closed); a chain without a validated
 * principal is {@code 500} (misconfiguration, caught at build time by {@link HttpLocalServer}).
 */
public final class PrincipalContextFilter implements Filter {

    public static final String READ_SCOPE = "imap-mcp:read";
    public static final String WRITE_SCOPE = "imap-mcp:write";

    static final String INSUFFICIENT_SCOPE = "Bearer error=\"insufficient_scope\", scope=\"" + READ_SCOPE + "\"";
    static final String INTERNAL_BODY =
            "{\"jsonrpc\":\"2.0\",\"error\":{\"code\":-32603,\"message\":\"Internal error\"},\"id\":null}";
    static final String UNAVAILABLE_BODY =
            "{\"jsonrpc\":\"2.0\",\"error\":{\"code\":-32003,\"message\":\"Service unavailable\"},\"id\":null}";

    private static final Logger LOG = LoggerFactory.getLogger(PrincipalContextFilter.class);

    private final PrincipalTenants tenants;

    public PrincipalContextFilter(PrincipalTenants tenants) {
        this.tenants = Objects.requireNonNull(tenants, "tenants");
    }

    @Override
    public void doFilter(ServletRequest req, ServletResponse res, FilterChain chain)
            throws IOException, ServletException {
        HttpServletRequest request = (HttpServletRequest) req;
        HttpServletResponse response = (HttpServletResponse) res;
        if (!(request.getAttribute(JwtTokenFilter.PRINCIPAL_ATTRIBUTE) instanceof JwtPrincipal principal)) {
            // wiring error (no token filter before this one), not a client problem: no challenge, no detail
            LOG.error("Principal filter reached without a validated principal (misconfigured filter chain)");
            send(response, HttpServletResponse.SC_INTERNAL_SERVER_ERROR, INTERNAL_BODY);
            return;
        }
        Set<String> scopes = scopes(principal);
        if (!scopes.contains(READ_SCOPE)) {
            response.setHeader("WWW-Authenticate", INSUFFICIENT_SCOPE);
            Forbidden.send(response);
            return;
        }
        Optional<PrincipalTenants.Tenant> tenant;
        try {
            tenant = tenants.find(principal.issuer(), principal.subject());
        } catch (RuntimeException e) {
            LOG.warn("Tenant lookup failed ({})", e.getClass().getSimpleName());
            send(response, HttpServletResponse.SC_SERVICE_UNAVAILABLE, UNAVAILABLE_BODY);
            return;
        }
        if (tenant.isPresent() && TenantId.NONE.equals(tenant.get().id())) {
            // the sentinel of "owns nothing" must never be a real tenant: fail closed
            LOG.warn("Tenant lookup failed ({})", "reserved_tenant_id");
            send(response, HttpServletResponse.SC_SERVICE_UNAVAILABLE, UNAVAILABLE_BODY);
            return;
        }
        if (tenant.isPresent() && tenant.get().disabled()) {
            Forbidden.send(response);
            return;
        }
        TenantId id = tenant.map(PrincipalTenants.Tenant::id).orElse(TenantId.NONE);
        request.setAttribute(BearerTokenFilter.ACCOUNT_ATTRIBUTE, new AccountContext(id, scopes));
        chain.doFilter(req, res);
    }

    /** The {@code scope} claim (space-separated string) and the {@code scp} claim (string or list of strings). */
    static Set<String> scopes(JwtPrincipal principal) {
        Set<String> out = new HashSet<>();
        if (principal.claims().get("scope") instanceof String s) {
            addAll(out, s);
        }
        Object scp = principal.claims().get("scp");
        if (scp instanceof String s) {
            addAll(out, s);
        } else if (scp instanceof List<?> list) {
            list.stream().filter(String.class::isInstance).map(String.class::cast).forEach(out::add); // whole elements
        }
        return out;
    }

    private static void send(HttpServletResponse response, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        response.setStatus(status);
        response.setContentType("application/json");
        response.setCharacterEncoding("UTF-8");
        response.setContentLength(bytes.length);
        response.getOutputStream().write(bytes);
    }

    private static void addAll(Set<String> out, String spaceSeparated) {
        for (String part : spaceSeparated.split(" ")) {
            if (!part.isEmpty()) {
                out.add(part);
            }
        }
    }
}
