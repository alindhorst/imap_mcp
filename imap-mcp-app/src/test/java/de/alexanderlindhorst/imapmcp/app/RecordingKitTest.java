package de.alexanderlindhorst.imapmcp.app;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** #434: the connector session recording kit (Caddy capture snippet, converter, README section). No containers. */
class RecordingKitTest {

    // Surefire runs with the module directory as working directory.
    private static final Path DOCKER = Path.of("..", "dist", "docker");
    private static final Path REDACT = DOCKER.resolve("tools").resolve("Redact.java").toAbsolutePath().normalize();
    private static final JsonMapper MAPPER = JsonMapper.builder().build();
    private static final List<String> NAMES = List.of("code", "state", "code_challenge", "nonce", "session_code",
            "tab_id", "client_data", "execution", "session_state", "id_token_hint", "login_hint");

    // Synthetic canaries; none of them may reach the fixture.
    private static final String ACCESS = "canary-access-token-AAAA";
    private static final String REFRESH = "canary-refresh-token-BBBB";
    private static final String CODE = "canary-auth-code-CCCC";
    private static final String VERIFIER = "canary-pkce-verifier-DDDD";
    private static final String CHALLENGE = "canary-pkce-challenge-EEEE";
    private static final String COOKIE = "canary-session-cookie-FFFF";
    private static final String IP = "203.0.113.77";
    private static final String UNKNOWN = "canary-unknown-value-GGGG";
    private static final List<String> CANARIES = List.of(ACCESS, REFRESH, CODE, VERIFIER, CHALLENGE, COOKIE, IP, UNKNOWN);

    private record Result(int exit, String output) {
    }

    private static String entry(double ts, String host, String method, String uri, String reqHeaders, int status,
            String respHeaders) {
        return "{\"level\":\"info\",\"ts\":" + ts + ",\"logger\":\"http.log.access.connector_capture\","
                + "\"msg\":\"handled request\",\"request\":{\"remote_ip\":\"" + IP + "\",\"remote_port\":\"5555\","
                + "\"client_ip\":\"" + IP + "\",\"proto\":\"HTTP/2.0\",\"method\":\"" + method + "\",\"host\":\"" + host
                + "\",\"uri\":\"" + uri + "\",\"headers\":{" + reqHeaders + "},\"tls\":{\"server_name\":\"" + host + "\"}},"
                + "\"bytes_read\":0,\"user_id\":\"\",\"duration\":0.01,\"size\":10,\"status\":" + status
                + ",\"resp_headers\":{" + respHeaders + "}}";
    }

    private static String rawLog() {
        return String.join("\n",
                entry(1700000000.000, "mcp.example.org", "POST", "/mcp",
                        "\"Accept\":[\"application/json\"],\"Content-Type\":[\"application/json\"],"
                                + "\"User-Agent\":[\"Claude-User\"],\"Authorization\":[\"Bearer " + ACCESS + "\"],"
                                + "\"Mcp-Session-Id\":[\"" + COOKIE + "\"],\"Mcp-Protocol-Version\":[\"2025-06-18\"],"
                                + "\"X-Unexpected\":[\"" + UNKNOWN + "\"]",
                        401, "\"Www-Authenticate\":[\"Bearer resource_metadata=\\\"https://mcp.example.org/x\\\"\"],"
                                + "\"Set-Cookie\":[\"s=" + COOKIE + "\"]"),
                entry(1700000001.500, "idp.example.org", "GET",
                        "/realms/r/protocol/openid-connect/auth?response_type=code&client_id=claude-connector"
                                + "&redirect_uri=https%3A%2F%2Fmcp.example.org%2Faccounts%2Fcallback&scope=openid+a"
                                + "&scope=b&code_challenge=" + CHALLENGE + "&code_challenge_method=S256&state=" + CODE
                                + "&foo=" + UNKNOWN + "&prompt=consent&flag",
                        "\"User-Agent\":[\"Mozilla/5.0 (X11; Linux) Firefox/130\"],\"Cookie\":[\"c=" + COOKIE + "\"],"
                                + "\"Origin\":[\"https://idp.example.org\"],\"Accept\":[\"text/html\"]",
                        302, "\"Location\":[\"https://idp.example.org/x?code=" + CODE + "\"]"),
                entry(1700000302.250, "idp.example.org", "POST", "/realms/r/protocol/openid-connect/token"
                                + "?code_verifier=" + VERIFIER + "&refresh_token=" + REFRESH + "&code=" + CODE,
                        "\"Content-Type\":[\"application/x-www-form-urlencoded\"]", 200, "")) + "\n";
    }

    private static Result run(Path dir, String raw, Map<String, String> env) throws Exception {
        Path in = dir.resolve("raw.log");
        Files.writeString(in, raw, StandardCharsets.UTF_8);
        return runOn(dir, in, dir.resolve("fixture.jsonl"), env);
    }

    private static Result runOn(Path dir, Path in, Path out, Map<String, String> env) throws Exception {
        String java = Path.of(System.getProperty("java.home"), "bin", "java").toString();
        ProcessBuilder pb = new ProcessBuilder(java, REDACT.toString(), in.toString(), out.toString())
                .directory(dir.toFile()).redirectErrorStream(true);
        pb.environment().remove("MCP_HOST");
        pb.environment().remove("IDP_HOST");
        pb.environment().putAll(env);
        Process p = pb.start();
        String output = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        return new Result(p.waitFor(), output);
    }

    private static final Map<String, String> HOSTS = Map.of("MCP_HOST", "mcp.example.org", "IDP_HOST", "idp.example.org");

    private static List<JsonNode> lines(Path file) throws IOException {
        List<JsonNode> out = new ArrayList<>();
        for (String l : Files.readAllLines(file, StandardCharsets.UTF_8)) {
            out.add(MAPPER.readTree(l));
        }
        return out;
    }

    @Test
    void theFixtureContainsNoCanaryAndNoHostName(@TempDir Path dir) throws Exception {
        Result r = run(dir, rawLog(), HOSTS);
        assertThat(r.exit()).as(r.output()).isZero();
        String fixture = Files.readString(dir.resolve("fixture.jsonl"));
        for (String canary : CANARIES) {
            assertThat(fixture).as(canary).doesNotContain(canary);
            assertThat(r.output()).as("console output: " + canary).doesNotContain(canary);
        }
        assertThat(fixture).doesNotContain("example.org").doesNotContain("Cookie").doesNotContain("Set-Cookie")
                .doesNotContain("Location").doesNotContain("X-Unexpected").doesNotContain("remote").doesNotContain("Bearer");
    }

    @Test
    void headerAndRequestFieldsFollowTheFormat(@TempDir Path dir) throws Exception {
        assertThat(run(dir, rawLog(), HOSTS).exit()).isZero();
        List<JsonNode> l = lines(dir.resolve("fixture.jsonl"));
        assertThat(l).hasSize(4);
        assertThat(l.get(0).get("format").asString()).isEqualTo("imap-mcp-connector-session");
        assertThat(l.get(0).get("version").asInt()).isEqualTo(1);
        assertThat(l.get(0).get("source").asString()).matches("recorded \\d{4}-\\d{2}-\\d{2}");

        JsonNode a = l.get(1);
        assertThat(a.get("seq").asInt()).isEqualTo(1);
        assertThat(a.get("relativeMs").asLong()).isZero();
        assertThat(a.get("host").asString()).isEqualTo("mcp");
        assertThat(a.get("method").asString()).isEqualTo("POST");
        assertThat(a.get("path").asString()).isEqualTo("/mcp");
        assertThat(a.get("query").isArray()).isTrue();
        assertThat(a.get("query")).isEmpty();
        assertThat(a.get("headers").get("Authorization").asString()).isEqualTo("present");
        assertThat(a.get("headers").get("Mcp-Session-Id").asString()).isEqualTo("present");
        assertThat(a.get("headers").get("User-Agent").asString()).isEqualTo("Claude-User");
        assertThat(a.get("headers").get("MCP-Protocol-Version").asString()).isEqualTo("2025-06-18");
        assertThat(a.get("headers").get("Content-Type").asString()).isEqualTo("application/json");
        assertThat(a.get("status").asInt()).isEqualTo(401);
        assertThat(a.get("wwwAuthenticate").asBoolean()).isTrue();

        JsonNode b = l.get(2);
        assertThat(b.get("relativeMs").asLong()).isEqualTo(1500);
        assertThat(b.get("host").asString()).isEqualTo("idp");
        assertThat(b.get("headers").get("User-Agent").asString()).isEqualTo("browser");
        assertThat(b.get("headers").get("Origin").asString()).isEqualTo("https://<IDP_HOST>");
        assertThat(b.get("headers").has("Authorization")).isFalse();
        assertThat(b.get("wwwAuthenticate").asBoolean()).isFalse();

        assertThat(l.get(3).get("relativeMs").asLong()).isEqualTo(302250);
    }

    @Test
    void allowlistedQueryValuesSurviveAndEverythingElseKeepsOnlyItsName(@TempDir Path dir) throws Exception {
        assertThat(run(dir, rawLog(), HOSTS).exit()).isZero();
        JsonNode q = lines(dir.resolve("fixture.jsonl")).get(2).get("query");
        List<String> pairs = new ArrayList<>();
        for (JsonNode p : q) {
            pairs.add(p.get("name").asString() + "=" + p.get("value").asString());
        }
        assertThat(pairs).containsExactly("response_type=code", "client_id=claude-connector",
                "redirect_uri=https://<MCP_HOST>/accounts/callback", "scope=openid a", "scope=b",
                "code_challenge=<redacted>", "code_challenge_method=S256", "state=<redacted>", "foo=<redacted>",
                "prompt=consent", "flag=<redacted>");
    }

    @Test
    void theHostNamesInPathsAreReplaced(@TempDir Path dir) throws Exception {
        String raw = entry(1700000000.0, "mcp.example.org", "GET", "/mcp.example.org/x/idp.example.org", "", 404, "") + "\n";
        assertThat(run(dir, raw, HOSTS).exit()).isZero();
        assertThat(lines(dir.resolve("fixture.jsonl")).get(1).get("path").asString()).isEqualTo("/<MCP_HOST>/x/<IDP_HOST>");
    }

    @Test
    void aMalformedLineAfterGoodLinesFailsAndLeavesNoFixtureAndNoTempFile(@TempDir Path dir) throws Exception {
        Result r = run(dir, rawLog() + "{\"ts\": 1.0, \"request\": {oops\n" + rawLog(), HOSTS);
        assertThat(r.exit()).isNotZero();
        assertThat(r.output()).doesNotContain(UNKNOWN).doesNotContain(ACCESS);
        try (Stream<Path> s = Files.list(dir)) {
            assertThat(s.map(p -> p.getFileName().toString())).containsExactly("raw.log");
        }
    }

    @Test
    void aFailedConversionLeavesAnExistingFixtureUntouched(@TempDir Path dir) throws Exception {
        Path out = dir.resolve("fixture.jsonl");
        Files.writeString(out, "previous\n");
        Path in = dir.resolve("raw.log");
        Files.writeString(in, rawLog() + "not json\n");
        assertThat(runOn(dir, in, out, HOSTS).exit()).isNotZero();
        assertThat(Files.readString(out)).isEqualTo("previous\n");
        try (Stream<Path> s = Files.list(dir)) {
            assertThat(s.count()).isEqualTo(2);
        }
    }

    @Test
    void aLineThatIsNotACaddyAccessEntryFails(@TempDir Path dir) throws Exception {
        assertThat(run(dir, rawLog() + "{\"level\":\"info\",\"msg\":\"something else\"}\n", HOSTS).exit()).isNotZero();
        assertThat(dir.resolve("fixture.jsonl")).doesNotExist();
    }

    @Test
    void anUnknownHostOrMissingHostVariablesFail(@TempDir Path dir) throws Exception {
        String raw = entry(1700000000.0, "other.example.net", "GET", "/", "", 200, "") + "\n";
        assertThat(run(dir, raw, HOSTS).exit()).isNotZero();
        assertThat(dir.resolve("fixture.jsonl")).doesNotExist();
        assertThat(run(dir, rawLog(), Map.of("MCP_HOST", "mcp.example.org")).exit()).isNotZero();
        assertThat(run(dir, rawLog(), Map.of("MCP_HOST", "mcp.example.org", "IDP_HOST", "")).exit()).isNotZero();
        assertThat(run(dir, rawLog(), Map.of()).exit()).isNotZero();
        assertThat(dir.resolve("fixture.jsonl")).doesNotExist();
    }

    @Test
    void wrongArgumentCountFails(@TempDir Path dir) throws Exception {
        String java = Path.of(System.getProperty("java.home"), "bin", "java").toString();
        Process p = new ProcessBuilder(java, REDACT.toString()).directory(dir.toFile()).redirectErrorStream(true).start();
        p.getInputStream().readAllBytes();
        assertThat(p.waitFor()).isNotZero();
    }

    // ---- capture.caddy ----

    private static String read(Path p) throws IOException {
        return Files.readString(p, StandardCharsets.UTF_8);
    }

    @Test
    void captureSnippetIsNotImportedByEitherShippedCaddyfile() throws IOException {
        assertThat(DOCKER.resolve("caddy").resolve("capture.caddy")).exists();
        for (String site : List.of("public", "local")) {
            assertThat(read(DOCKER.resolve("caddy").resolve(site).resolve("Caddyfile")))
                    .as(site).doesNotContain("capture").doesNotContain("import /");
        }
        // nothing else mounts or copies it by default
        assertThat(read(DOCKER.resolve("compose.yaml"))).doesNotContain("capture");
    }

    @Test
    void captureSnippetDeletesAndReplacesEveryDocumentedField() throws IOException {
        String text = read(DOCKER.resolve("caddy").resolve("capture.caddy"));
        for (String d : List.of("request>headers>Authorization delete", "request>headers>Cookie delete",
                "resp_headers>Set-Cookie delete", "resp_headers>Location delete", "request>remote_ip delete",
                "request>client_ip delete")) {
            assertThat(text).as(d).contains(d);
        }
        assertThat(text).contains("request>uri query");
        for (String n : NAMES) {
            assertThat(text).as(n).containsPattern("(?m)^\\s*replace " + n + " REDACTED\\s*$");
        }
        assertThat(text).containsPattern("(?s)output file [^\\n{]+\\{[^}]*mode 0600");
        assertThat(text).contains("format filter").contains("wrap json");
    }

    // ---- README ----

    @Test
    void readmeHasTheRecordingSectionWithResolvingLinksAndNoSecret() throws IOException {
        Path readme = DOCKER.resolve("README.md");
        String text = read(readme);
        Matcher m = Pattern.compile("(?m)^## Recording a connector session\\s*$").matcher(text);
        assertThat(m.find()).as("section heading").isTrue();
        int start = m.end();
        Matcher next = Pattern.compile("(?m)^## ").matcher(text);
        String section = next.find(start) ? text.substring(start, next.start()) : text.substring(start);
        assertThat(section).contains("capture.caddy").contains("Redact.java").contains("shred").contains("kcadm")
                .contains("/accounts").contains("5 minutes").contains("Review").contains("umask 077").contains("mktemp -d").contains("shred -u").contains("rmdir")
                .doesNotContain("/tmp/connector.log").contains("open owner decision")
                .doesNotContainPattern("#\\d{3}");
        Matcher links = Pattern.compile("\\]\\(([^)#\\s]+)(#[^)]*)?\\)").matcher(section);
        while (links.find()) {
            if (!links.group(1).contains("://")) {
                assertThat(readme.resolveSibling(links.group(1))).as(links.group(1)).exists();
            }
        }
        assertThat(DocChecks.findSecretLikeToken(section)).isEmpty();
        assertThat(text.chars().filter(ch -> (ch < 0x20 && ch != '\n' && ch != '\r' && ch != '\t')
                || (ch >= 0x200B && ch <= 0x200F) || (ch >= 0x202A && ch <= 0x202E) || (ch >= 0x2066 && ch <= 0x2069)
                || ch == 0xFEFF).count()).isZero();
    }
}
