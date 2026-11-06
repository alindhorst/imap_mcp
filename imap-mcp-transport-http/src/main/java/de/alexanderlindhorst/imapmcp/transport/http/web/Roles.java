package de.alexanderlindhorst.imapmcp.transport.http.web;

import java.util.Collection;
import java.util.Map;

/** Reads a role list from a claim path such as {@code realm_access.roles} (nested objects, dot separated). */
final class Roles {

    private Roles() {
    }

    static boolean has(Map<String, Object> claims, String claimPath, String role) {
        Object current = claims;
        for (String part : claimPath.split("\\.")) {
            if (!(current instanceof Map<?, ?> m)) {
                return false;
            }
            current = m.get(part);
        }
        if (current instanceof String s) {
            return s.equals(role);
        }
        if (current instanceof Collection<?> c) {
            return c.stream().anyMatch(o -> o instanceof String s && s.equals(role));
        }
        return false;
    }
}
