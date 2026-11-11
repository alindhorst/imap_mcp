package de.alexanderlindhorst.imapmcp.it;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** #435: the conformance comparison of a connector trace with a committed fixture. No stack, no Docker. */
class ConnectorConformanceTest {

    private static ConnectorSessionFixture assumed() throws IOException {
        try (InputStream in = ConnectorConformanceTest.class
                .getResourceAsStream("/connector/assumed-claude-session.jsonl")) {
            assertThat(in).isNotNull();
            return ConnectorSessionFixture.read(new String(in.readAllBytes(), StandardCharsets.UTF_8));
        }
    }

    private static ConnectorSessionFixture with(ConnectorSessionFixture f, List<ConnectorSessionFixture.Request> rs) {
        return new ConnectorSessionFixture(f.header(), rs);
    }

    @Test
    void anIdenticalTraceConforms() throws IOException {
        assertThat(ConnectorConformance.firstDifference(assumed(), assumed())).isEmpty();
    }

    @Test
    void timingAndSequenceNumbersAreNotCompared() throws IOException {
        ConnectorSessionFixture f = assumed();
        List<ConnectorSessionFixture.Request> rs = new ArrayList<>();
        for (ConnectorSessionFixture.Request r : f.requests()) {
            rs.add(new ConnectorSessionFixture.Request(r.seq() + 100, r.relativeMs() + 7, r.host(), r.method(),
                    r.path(), r.query(), r.headers(), r.status(), r.wwwAuthenticate()));
        }
        assertThat(ConnectorConformance.firstDifference(f, with(f, rs))).isEmpty();
    }

    @Test
    void queryParametersAreComparedByNameIndependentOfOrder() throws IOException {
        ConnectorSessionFixture f = assumed();
        List<ConnectorSessionFixture.Request> rs = new ArrayList<>(f.requests());
        ConnectorSessionFixture.Request auth = rs.get(3);
        List<ConnectorSessionFixture.Param> reversed = new ArrayList<>(auth.query());
        java.util.Collections.reverse(reversed);
        rs.set(3, new ConnectorSessionFixture.Request(auth.seq(), auth.relativeMs(), auth.host(), auth.method(),
                auth.path(), reversed, auth.headers(), auth.status(), auth.wwwAuthenticate()));
        assertThat(ConnectorConformance.firstDifference(f, with(f, rs))).isEmpty();
    }

    @Test
    void aChangedParameterNameFailsWithTheFirstDifferingRequest() throws IOException {
        ConnectorSessionFixture f = assumed();
        List<ConnectorSessionFixture.Request> rs = new ArrayList<>(f.requests());
        ConnectorSessionFixture.Request auth = rs.get(3);
        List<ConnectorSessionFixture.Param> q = new ArrayList<>(auth.query());
        q.set(0, new ConnectorSessionFixture.Param("responsetype", q.get(0).value()));
        rs.set(3, new ConnectorSessionFixture.Request(auth.seq(), auth.relativeMs(), auth.host(), auth.method(),
                auth.path(), q, auth.headers(), auth.status(), auth.wwwAuthenticate()));
        String diff = ConnectorConformance.firstDifference(f, with(f, rs)).orElseThrow();
        assertThat(diff).contains("request 4").contains("response_type").contains("responsetype")
                .contains("/realms/imap-mcp/protocol/openid-connect/auth");
    }

    @Test
    void aRemovedRequestFails() throws IOException {
        ConnectorSessionFixture f = assumed();
        List<ConnectorSessionFixture.Request> rs = new ArrayList<>(f.requests());
        rs.remove(4);
        String diff = ConnectorConformance.firstDifference(f, with(f, rs)).orElseThrow();
        assertThat(diff).contains("request 5").contains("login-actions/authenticate");
    }

    @Test
    void anAdditionalRequestFails() throws IOException {
        ConnectorSessionFixture f = assumed();
        List<ConnectorSessionFixture.Request> rs = new ArrayList<>(f.requests());
        rs.add(rs.getLast());
        assertThat(ConnectorConformance.firstDifference(f, with(f, rs))).isPresent();
    }

    @Test
    void aChangedHeaderValueAStatusAndAMissingHeaderFail() throws IOException {
        ConnectorSessionFixture f = assumed();
        ConnectorSessionFixture.Request first = f.requests().getFirst();
        Map<String, String> h = new LinkedHashMap<>(first.headers());
        h.put("Accept", "text/plain");
        assertThat(ConnectorConformance.firstDifference(f, replaceFirst(f, new ConnectorSessionFixture.Request(
                first.seq(), first.relativeMs(), first.host(), first.method(), first.path(), first.query(), h,
                first.status(), first.wwwAuthenticate()))).orElseThrow()).contains("Accept");
        h = new LinkedHashMap<>(first.headers());
        h.remove("Content-Type");
        assertThat(ConnectorConformance.firstDifference(f, replaceFirst(f, new ConnectorSessionFixture.Request(
                first.seq(), first.relativeMs(), first.host(), first.method(), first.path(), first.query(), h,
                first.status(), first.wwwAuthenticate()))).orElseThrow()).contains("Content-Type");
        assertThat(ConnectorConformance.firstDifference(f, replaceFirst(f, new ConnectorSessionFixture.Request(
                first.seq(), first.relativeMs(), first.host(), first.method(), first.path(), first.query(),
                first.headers(), 200, first.wwwAuthenticate()))).orElseThrow()).contains("status").contains("401")
                .contains("200");
    }

    @Test
    void theDiffOfALeakingTraceDoesNotPrintTheRedactedOrAnyOtherValueBeyondTheFixtureFormat() throws IOException {
        ConnectorSessionFixture f = assumed();
        ConnectorSessionFixture.Request auth = f.requests().get(3);
        List<ConnectorSessionFixture.Param> q = new ArrayList<>(auth.query());
        q.set(0, new ConnectorSessionFixture.Param("response_type", "code"));
        q.add(new ConnectorSessionFixture.Param("extra", "<redacted>"));
        List<ConnectorSessionFixture.Request> rs = new ArrayList<>(f.requests());
        rs.set(3, new ConnectorSessionFixture.Request(auth.seq(), auth.relativeMs(), auth.host(), auth.method(),
                auth.path(), q, auth.headers(), auth.status(), auth.wwwAuthenticate()));
        assertThat(ConnectorConformance.firstDifference(f, with(f, rs)).orElseThrow()).contains("extra");
    }

    private static ConnectorSessionFixture replaceFirst(ConnectorSessionFixture f,
            ConnectorSessionFixture.Request r) {
        List<ConnectorSessionFixture.Request> rs = new ArrayList<>(f.requests());
        rs.set(0, r);
        return with(f, rs);
    }
}
