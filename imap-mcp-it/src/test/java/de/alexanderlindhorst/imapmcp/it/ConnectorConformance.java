package de.alexanderlindhorst.imapmcp.it;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;

/**
 * Compares a connector trace (the client's own, {@link ConnectorTrace}) with a committed fixture
 * ({@link ConnectorSessionFixture}, #435, SPEC 5.8 "Connector contract": any difference fails the test).
 *
 * <p>Per request, in order, these are compared: host role, method, path, status, whether the response carried
 * {@code WWW-Authenticate}, the headers (name and value, {@code Authorization} only as {@code present}), and the
 * query parameters. The query parameters are compared as a multiset of (name, value) pairs, independent of their
 * order: the order of the parameters of an authorization request carries no meaning. The values are the trace
 * format's, i.e. allowlisted values after placeholder substitution, everything else {@code <redacted>}. {@code seq}
 * and {@code relativeMs} are not compared. The number of requests must be equal.
 *
 * <p>The rendered difference only uses what the trace format holds, so it carries no token.
 */
final class ConnectorConformance {

    private ConnectorConformance() {
    }

    /** The readable difference of the first differing request, or empty when the trace conforms. */
    static Optional<String> firstDifference(ConnectorSessionFixture expected, ConnectorSessionFixture actual) {
        List<ConnectorSessionFixture.Request> e = expected.requests();
        List<ConnectorSessionFixture.Request> a = actual.requests();
        for (int i = 0; i < Math.max(e.size(), a.size()); i++) {
            if (i >= a.size()) {
                return Optional.of("request " + (i + 1) + ": the fixture has " + describe(e.get(i))
                        + " but the trace ended after " + a.size() + " requests");
            }
            if (i >= e.size()) {
                return Optional.of("request " + (i + 1) + ": the trace has an additional " + describe(a.get(i))
                        + " after the fixture's " + e.size() + " requests");
            }
            List<String> why = differences(e.get(i), a.get(i));
            if (!why.isEmpty()) {
                return Optional.of("request " + (i + 1) + " differs\n  fixture: " + describe(e.get(i))
                        + "\n  trace:   " + describe(a.get(i)) + "\n  because: " + String.join("; ", why));
            }
        }
        return Optional.empty();
    }

    private static List<String> differences(ConnectorSessionFixture.Request e, ConnectorSessionFixture.Request a) {
        List<String> why = new ArrayList<>();
        if (!e.host().equals(a.host()) || !e.method().equals(a.method()) || !e.path().equals(a.path())) {
            why.add("host role, method or path");
        }
        if (e.status() != a.status()) {
            why.add("status " + e.status() + " expected, " + a.status() + " seen");
        }
        if (e.wwwAuthenticate() != a.wwwAuthenticate()) {
            why.add("WWW-Authenticate " + e.wwwAuthenticate() + " expected, " + a.wwwAuthenticate() + " seen");
        }
        Map<String, String> eh = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        eh.putAll(e.headers());
        Map<String, String> ah = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        ah.putAll(a.headers());
        for (String name : eh.keySet()) {
            if (!ah.containsKey(name)) {
                why.add("header " + name + " missing");
            } else if (!eh.get(name).equals(ah.get(name))) {
                why.add("header " + name + " is " + ah.get(name) + ", expected " + eh.get(name));
            }
        }
        for (String name : ah.keySet()) {
            if (!eh.containsKey(name)) {
                why.add("header " + name + " is not in the fixture");
            }
        }
        List<String> eq = sorted(e.query());
        List<String> aq = sorted(a.query());
        if (!eq.equals(aq)) {
            List<String> missing = new ArrayList<>(eq);
            aq.forEach(missing::remove);
            List<String> extra = new ArrayList<>(aq);
            eq.forEach(extra::remove);
            why.add("query parameters: missing " + missing + ", unexpected " + extra);
        }
        return why;
    }

    private static List<String> sorted(List<ConnectorSessionFixture.Param> query) {
        return query.stream().map(p -> p.name() + "=" + p.value()).sorted().toList();
    }

    private static String describe(ConnectorSessionFixture.Request r) {
        return r.method() + " " + r.host() + " " + r.path() + " query" + r.query().stream().map(
                ConnectorSessionFixture.Param::name).toList() + " headers" + r.headers() + " -> " + r.status();
    }
}
