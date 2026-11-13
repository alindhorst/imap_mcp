package de.alexanderlindhorst.imapmcp.app;

import static org.assertj.core.api.Assertions.assertThat;

import de.alexanderlindhorst.imapmcp.app.config.PublicConfigLoader;
import de.alexanderlindhorst.imapmcp.app.config.UpstreamProvider;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.dataformat.yaml.YAMLMapper;

/** #416: static checks of the container test environment under dist/docker (no containers are started). */
class DistDockerTest {

    // Surefire runs with the module directory as working directory.
    private static final Path DOCKER = Path.of("..", "dist", "docker");
    private static final Pattern DIGEST = Pattern.compile("@sha256:[0-9a-f]{64}");

    private static String read(Path p) throws IOException {
        return Files.readString(p, StandardCharsets.UTF_8);
    }

    private static String digestOf(String text, String imagePrefix) {
        Matcher m = Pattern.compile(Pattern.quote(imagePrefix) + "[^\\s\"']*?(@sha256:[0-9a-f]{64})").matcher(text);
        assertThat(m.find()).as("pinned reference of " + imagePrefix).isTrue();
        return m.group(1);
    }

    private static List<Path> versionedFiles() throws IOException {
        try (Stream<Path> s = Files.walk(DOCKER)) {
            return s.filter(Files::isRegularFile).filter(DistDockerTest::tracked).toList();
        }
    }

    /** Files the owner generated locally (.env, certificates, config.yaml) are not versioned and not checked. */
    private static boolean tracked(Path p) {
        try {
            Process git = new ProcessBuilder("git", "check-ignore", "-q", p.toString()).redirectErrorStream(true).start();
            return git.waitFor() != 0;
        } catch (IOException | InterruptedException e) {
            return true;
        }
    }

    @Test
    void configExampleCarriesACommentedOutUpstreamOauthExampleThatLoadsWhenUncommented(@TempDir Path tmp)
            throws IOException {
        Path example = DOCKER.resolve("config.yaml.example");
        assertThat(PublicConfigLoader.load(example, Map.of()).oauth().upstreamProviders()).isEmpty();
        StringBuilder out = new StringBuilder();
        boolean in = false;
        for (String line : read(example).split("\n", -1)) {
            in = in ? line.startsWith("  #") : line.equals("  # upstream:");
            out.append(in ? "  " + line.substring(4) : line).append('\n');
        }
        Path file = tmp.resolve("config.yaml");
        // the owner adds the provider IMAP hosts to ssrf.allowedHosts, as the example's comment says
        Files.writeString(file, out.toString().replace("allowedHosts: [\"imap.example.com\"]",
                "allowedHosts: [\"imap.example.com\", \"imap.gmail.com\", \"outlook.office365.com\"]"));
        var providers = PublicConfigLoader.load(file, Map.of()).oauth().upstreamProviders();
        assertThat(providers).extracting(UpstreamProvider::id).containsExactly("gmail", "work-microsoft", "corp");
        assertThat(providers).allSatisfy(p -> assertThat(p.clientSecret().value()).matches("(env|file):.+"));
    }

    @Test
    void configExampleLoadsWithTheHttpPublicLoaderAndKeepsSecretsInEnvRefs() throws IOException {
        var c = PublicConfigLoader.load(DOCKER.resolve("config.yaml.example"), Map.of());
        assertThat(c.kms().provider()).isEqualTo("local");
        assertThat(c.purgeEnabled()).isFalse();
        assertThat(c.http().stateful()).isFalse();
        assertThat(c.imap().trace()).isFalse();
        assertThat(c.oauth().requiredRole()).isEqualTo("imap-mcp-user");
        assertThat(c.oauth().webClientId()).isEqualTo("imap-mcp-web");
        assertThat(c.oauth().resource()).endsWith("/mcp");
        assertThat(c.db().password().value()).isEqualTo("env:IMAPMCP_DB_PASSWORD");
        assertThat(c.oauth().webClientSecret().value()).isEqualTo("env:WEB_CLIENT_SECRET");
        assertThat(c.security().handleKeys().getFirst().key().value()).isEqualTo("env:HANDLE_KEY_1");
        assertThat(c.kms().keks().getFirst().key().value()).isEqualTo("env:KEK_1");
        assertThat(read(DOCKER.resolve("config.yaml.example"))).doesNotContain("trace");
    }

    @Test
    void digestsEqualThoseOfTheIntegrationTestsAndTheHttpLocalDockerfile() throws IOException {
        String it = read(Path.of("..", "imap-mcp-it", "src", "test", "java", "de", "alexanderlindhorst", "imapmcp",
                "it", "ItImages.java"));
        String local = read(Path.of("src", "main", "docker", "Dockerfile"));
        String compose = read(DOCKER.resolve("compose.yaml"));
        String dockerfile = read(DOCKER.resolve("Dockerfile"));

        assertThat(digestOf(compose, "quay.io/keycloak/keycloak:26.4")).isEqualTo(digestOf(it, "quay.io/keycloak/keycloak:26.4"));
        assertThat(digestOf(compose, "postgres:17-alpine")).isEqualTo(digestOf(it, "postgres:17-alpine"));
        assertThat(digestOf(dockerfile, "eclipse-temurin:25.0.5_8-jre-noble"))
                .isEqualTo(digestOf(local, "eclipse-temurin:25.0.5_8-jre-noble"));
        assertThat(DIGEST.matcher(compose).results().count()).as("keycloak, postgres, caddy").isGreaterThanOrEqualTo(3);
        // every image reference is pinned and fully qualified
        for (String line : compose.lines().filter(l -> l.stripLeading().startsWith("image:")).toList()) {
            if (line.contains("localhost/")) {
                continue;
            }
            assertThat(line).as("pinned").containsPattern(DIGEST);
            assertThat(line).as("fully qualified").containsPattern("image:\\s*(docker\\.io|quay\\.io|ghcr\\.io)/");
        }
    }

    @Test
    void composeHasTheExpectedServicesAndNoEnvFile() throws IOException {
        JsonNode compose = YAMLMapper.builder().build().readTree(read(DOCKER.resolve("compose.yaml")));
        JsonNode services = compose.get("services");
        assertThat(services.propertyNames()).containsExactlyInAnyOrder("postgres", "keycloak", "app", "caddy");
        assertThat(services.get("caddy").get("profiles").get(0).asString()).isEqualTo("proxy");
        assertThat(services.get("app").get("read_only").asBoolean()).isTrue();
        for (var name : services.propertyNames()) {
            assertThat(services.get(name).has("env_file")).as(name + " env_file").isFalse();
            assertThat(services.get(name).has("healthcheck") || name.equals("caddy")).as(name + " healthcheck").isTrue();
        }
        String text = read(DOCKER.resolve("compose.yaml"));
        assertThat(text).contains("--import-realm").doesNotContain("IMAP_MCP_MODE").doesNotContain("trace");
    }

    @Test
    void realmDefinesTheClientsScopesAndMappersWithoutUsersOrSecrets() throws IOException {
        Path file = DOCKER.resolve("keycloak").resolve("import").resolve("imap-mcp-realm.json");
        JsonNode realm = JsonMapper.builder().build().readTree(read(file));
        assertThat(realm.get("realm").asString()).isEqualTo("imap-mcp");
        assertThat(realm.get("registrationAllowed").asBoolean()).isFalse();
        assertThat(realm.get("bruteForceProtected").asBoolean()).isTrue();
        assertThat(realm.get("defaultSignatureAlgorithm").asString()).isEqualTo("RS256");
        assertThat(realm.has("users")).isFalse();
        assertThat(realm.get("roles").get("realm").get(0).get("name").asString()).isEqualTo("imap-mcp-user");

        JsonNode connector = client(realm, "claude-connector");
        assertThat(connector.get("publicClient").asBoolean()).isTrue();
        assertThat(connector.get("attributes").get("pkce.code.challenge.method").asString()).isEqualTo("S256");
        assertThat(strings(connector.get("redirectUris"))).contains("https://claude.ai/api/mcp/auth_callback");
        assertThat(strings(connector.get("optionalClientScopes"))).containsExactlyInAnyOrder("imap-mcp:read", "imap-mcp:write");
        assertThat(strings(connector.get("defaultClientScopes"))).contains("imap-mcp-audience");
        assertThat(connector.get("directAccessGrantsEnabled").asBoolean()).isFalse();

        JsonNode web = client(realm, "imap-mcp-web");
        assertThat(web.get("publicClient").asBoolean()).isFalse();
        assertThat(web.get("secret").asString()).isEqualTo("${REALM_WEB_CLIENT_SECRET}");
        assertThat(strings(web.get("redirectUris"))).containsExactly("${REALM_WEB_REDIRECT_URI}");
        assertThat(web.get("attributes").get("pkce.code.challenge.method").asString()).isEqualTo("S256");
        JsonNode roleMapper = web.get("protocolMappers").get(0);
        assertThat(roleMapper.get("config").get("claim.name").asString()).isEqualTo("realm_access.roles");
        assertThat(roleMapper.get("config").get("id.token.claim").asString()).isEqualTo("true");

        JsonNode audience = scope(realm, "imap-mcp-audience");
        assertThat(audience.get("protocolMappers").get(0).get("config").get("included.custom.audience").asString())
                .isEqualTo("${REALM_MCP_RESOURCE}");
        assertThat(scope(realm, "imap-mcp:read")).isNotNull();
        assertThat(scope(realm, "imap-mcp:write")).isNotNull();
        JsonNode basic = scope(realm, "basic");
        List<String> mappers = new ArrayList<>();
        basic.get("protocolMappers").forEach(m -> mappers.add(m.get("name").asString()));
        assertThat(mappers).containsExactlyInAnyOrder("sub", "auth_time");

        // the only values that look like secrets or credentials are whole-value ${...} placeholders
        String text = read(file);
        assertThat(text).doesNotContain("\"credentials\"").doesNotContain("\"password\"");
        Matcher m = Pattern.compile("\"(?:secret|value|password)\"\\s*:\\s*\"([^\"]*)\"").matcher(text);
        while (m.find()) {
            assertThat(m.group(1)).matches("\\$\\{[A-Z_]+}");
        }
        Matcher ph = Pattern.compile("\\$\\{([^}]*)}").matcher(text);
        Set<String> names = new java.util.HashSet<>();
        while (ph.find()) {
            names.add(ph.group(1));
        }
        assertThat(names).containsExactlyInAnyOrder("REALM_MCP_RESOURCE", "REALM_WEB_REDIRECT_URI", "REALM_WEB_CLIENT_SECRET");
        assertThat(Pattern.compile("\"[^\"]*\\$\\{[^}]*}[^\"]+\"|\"[^\"]+\\$\\{[^}]*}[^\"]*\"").matcher(text).find())
                .as("placeholders only as whole JSON string values").isFalse();
        try (Stream<Path> s = Files.list(file.getParent())) {
            assertThat(s.map(p -> p.getFileName().toString()).toList()).allMatch(n -> n.endsWith(".json"));
        }
    }

    private static JsonNode client(JsonNode realm, String id) {
        for (JsonNode c : realm.get("clients")) {
            if (id.equals(c.get("clientId").asString())) {
                return c;
            }
        }
        throw new AssertionError("client " + id);
    }

    private static JsonNode scope(JsonNode realm, String name) {
        for (JsonNode c : realm.get("clientScopes")) {
            if (name.equals(c.get("name").asString())) {
                return c;
            }
        }
        throw new AssertionError("scope " + name);
    }

    private static List<String> strings(JsonNode array) {
        List<String> out = new ArrayList<>();
        array.forEach(n -> out.add(n.asString()));
        return out;
    }

    @Test
    void noEnvFileIsCommittedAndTheIgnoreRulesCoverTheGeneratedFiles() throws IOException {
        String ignore = read(DOCKER.resolve(".gitignore"));
        for (String entry : List.of(".env", "jar/", "app/config.yaml", "truststore/*", "local-ca/")) {
            assertThat(ignore.lines().map(String::strip)).as(entry).contains(entry);
        }
        for (Path p : versionedFiles()) {
            String name = p.getFileName().toString();
            assertThat(name.equals(".env") || (name.startsWith(".env.") && !name.equals(".env.example")))
                    .as("committed " + p).isFalse();
            assertThat(name).doesNotEndWith(".key").doesNotEndWith(".p12").doesNotEndWith(".crt").doesNotEndWith(".pem");
        }
        // placeholders only: a secret variable has an empty value or a <placeholder>
        for (String line : read(DOCKER.resolve(".env.example")).lines().toList()) {
            if (line.startsWith("#") || line.isBlank()) {
                continue;
            }
            String[] kv = line.split("=", 2);
            if (kv[0].matches(".*(PASSWORD|SECRET|KEY|KEK).*")) {
                assertThat(kv.length == 1 || kv[1].isEmpty() || kv[1].matches("<[^>]+>")).as(kv[0]).isTrue();
            }
            assertThat(line).as("comments on their own lines").doesNotContain(" #");
        }
    }

    @Test
    void versionedFilesCarryNoControlCharactersAndNoInternalReferences() throws IOException {
        for (Path p : versionedFiles()) {
            String text = read(p);
            assertThat(text.chars().filter(ch -> (ch < 0x20 && ch != '\n' && ch != '\r' && ch != '\t')
                    || (ch >= 0x200B && ch <= 0x200F) || (ch >= 0x202A && ch <= 0x202E) || (ch >= 0x2066 && ch <= 0x2069)
                    || ch == 0xFEFF).count()).as(p.toString()).isZero();
            assertThat(text).as(p.toString()).doesNotContain("CLAUDE.md").doesNotContain("imap.trace");
        }
        assertThat(read(Path.of("..", "README.md"))).contains("dist/docker");
    }

    @Test
    void theAppDockerfileRunsAsUid10001InTheHttpPublicMode() throws IOException {
        String d = read(DOCKER.resolve("Dockerfile"));
        assertThat(d).contains("USER 10001").contains("HealthProbe").contains("\"--mode\", \"http-public\"")
                .contains("ARG JAR_FILE=jar/imap-mcp.jar");
        assertThat(read(DOCKER.resolve(".dockerignore")).lines().map(String::strip)).contains("*", "!jar/*.jar");
    }

    /** setup.sh validates host names and the ACME address before it writes anything (sed and .env injection). */
    @Test
    void setupRefusesInvalidInputsBeforeWritingAnything(@org.junit.jupiter.api.io.TempDir Path tmp) throws Exception {
        Path dir = Files.createDirectories(tmp.resolve("docker"));
        Files.createDirectories(dir.resolve("app"));
        for (String f : List.of("setup.sh", "config.yaml.example", "Dockerfile")) {
            Files.copy(DOCKER.resolve(f), dir.resolve(f));
        }
        dir.resolve("setup.sh").toFile().setExecutable(true);
        String[][] bad = {
            {"mail.example.org|x", "idp.example.org", "a@example.org", "imap.example.org"},
            {"Mail.example.org", "idp.example.org", "a@example.org", "imap.example.org"},
            {"mail.example.org", "idp", "a@example.org", "imap.example.org"},
            {"mail.example.org", "idp.example.org", "a b@example.org", "imap.example.org"},
            {"mail.example.org", "idp.example.org", "a&b@example.org", "imap.example.org"},
            {"mail.example.org", "idp.example.org", "a@example.org", "imap.example.org,bad_host.example.org"},
        };
        for (String[] b : bad) {
            ProcessBuilder pb = new ProcessBuilder("bash", dir.resolve("setup.sh").toString()).directory(dir.toFile())
                    .redirectErrorStream(true);
            pb.environment().put("MCP_HOST", b[0]);
            pb.environment().put("IDP_HOST", b[1]);
            pb.environment().put("ACME_EMAIL", b[2]);
            pb.environment().put("IMAP_HOSTS", b[3]);
            Process p = pb.start();
            p.getInputStream().readAllBytes();
            assertThat(p.waitFor()).as(String.join(" ", b)).isNotZero();
            assertThat(dir.resolve(".env")).doesNotExist();
            assertThat(dir.resolve("app").resolve("config.yaml")).doesNotExist();
        }
        ProcessBuilder ok = new ProcessBuilder("bash", dir.resolve("setup.sh").toString()).directory(dir.toFile())
                .redirectErrorStream(true);
        ok.environment().put("MCP_HOST", "mail.example.org");
        ok.environment().put("IDP_HOST", "idp.example.org");
        ok.environment().put("ACME_EMAIL", "a@example.org");
        ok.environment().put("IMAP_HOSTS", "imap.example.org, *.mail.example.net");
        Process p = ok.start();
        p.getInputStream().readAllBytes();
        assertThat(p.waitFor()).isZero();
        assertThat(dir.resolve(".env")).exists();
    }

    /** #425: the backend subnet is one variable, used for the network and for the Keycloak trusted proxies. */
    @Test
    void backendSubnetIsOneVariableInComposeAndNamedInTheDocs() throws IOException {
        String text = read(DOCKER.resolve("compose.yaml"));
        String expr = "${BACKEND_SUBNET:-172.28.0.0/24}";
        JsonNode compose = YAMLMapper.builder().build().readTree(text);
        assertThat(compose.get("networks").get("backend").get("ipam").get("config").get(0).get("subnet").asString())
                .isEqualTo(expr);
        assertThat(compose.get("services").get("keycloak").get("environment").get("KC_PROXY_TRUSTED_ADDRESSES")
                .asString()).isEqualTo(expr);
        assertThat(text.replace(expr, "")).doesNotContain("172.28.");
        assertThat(read(Path.of("..", "doc", "DEPLOYMENT.md"))).contains("BACKEND_SUBNET");
        assertThat(read(DOCKER.resolve("README.md"))).contains("BACKEND_SUBNET");
    }

    /** #425: setup.sh validates BACKEND_SUBNET as an IPv4 CIDR before writing, passes it on only when set. */
    @Test
    void setupValidatesAndPassesOnBackendSubnet(@org.junit.jupiter.api.io.TempDir Path tmp) throws Exception {
        Path dir = Files.createDirectories(tmp.resolve("docker"));
        Files.createDirectories(dir.resolve("app"));
        for (String f : List.of("setup.sh", "config.yaml.example", "Dockerfile")) {
            Files.copy(DOCKER.resolve(f), dir.resolve(f));
        }
        for (String bad : List.of("not-a-cidr", "10.0.0.0/33", "300.0.0.0/24", "10.0.0.0", "10.0.0.0/24 x", "10.0.0/24")) {
            assertThat(runSetup(dir, bad)).as(bad).isNotZero();
            assertThat(dir.resolve(".env")).doesNotExist();
            assertThat(dir.resolve("app").resolve("config.yaml")).doesNotExist();
        }
        assertThat(runSetup(dir, null)).isZero();
        assertThat(read(dir.resolve(".env"))).doesNotContain("BACKEND_SUBNET");
        Files.delete(dir.resolve(".env"));
        assertThat(runSetup(dir, "10.77.0.0/24")).isZero();
        assertThat(read(dir.resolve(".env"))).contains("\nBACKEND_SUBNET=10.77.0.0/24\n");
    }

    private static int runSetup(Path dir, String subnet) throws Exception {
        ProcessBuilder pb = new ProcessBuilder("bash", dir.resolve("setup.sh").toString()).directory(dir.toFile())
                .redirectErrorStream(true);
        pb.environment().put("MCP_HOST", "mail.example.org");
        pb.environment().put("IDP_HOST", "idp.example.org");
        pb.environment().put("ACME_EMAIL", "a@example.org");
        pb.environment().put("IMAP_HOSTS", "imap.example.org");
        pb.environment().remove("BACKEND_SUBNET");
        if (subnet != null) {
            pb.environment().put("BACKEND_SUBNET", subnet);
        }
        Process p = pb.start();
        p.getInputStream().readAllBytes();
        return p.waitFor();
    }

    /** #418: doc/DEPLOYMENT.md exists, is linked from both READMEs, its relative links resolve, and it stays clean. */
    @Test
    void deploymentGuideIsLinkedAndItsLinksResolve() throws IOException {
        Path guide = Path.of("..", "doc", "DEPLOYMENT.md");
        String text = read(guide);
        assertThat(read(Path.of("..", "README.md"))).contains("](doc/DEPLOYMENT.md)");
        assertThat(read(DOCKER.resolve("README.md"))).contains("](../../doc/DEPLOYMENT.md)");
        assertThat(text).contains("](../dist/docker/README.md)").contains("](../README.md#").contains("](SPEC.md");
        Matcher m = Pattern.compile("\\]\\(([^)#\\s]+)(#[^)]*)?\\)").matcher(text);
        int links = 0;
        while (m.find()) {
            if (m.group(1).contains("://")) {
                continue;
            }
            assertThat(guide.resolveSibling(m.group(1))).as(m.group(1)).exists();
            links++;
        }
        assertThat(links).isGreaterThanOrEqualTo(3);
        assertThat(text).doesNotContain("CLAUDE.md").doesNotContain("imap.trace");
        assertThat(text.chars().filter(ch -> (ch < 0x20 && ch != '\n' && ch != '\r' && ch != '\t')
                || (ch >= 0x200B && ch <= 0x200F) || (ch >= 0x202A && ch <= 0x202E) || (ch >= 0x2066 && ch <= 0x2069)
                || ch == 0xFEFF).count()).isZero();
    }

    /** #440: section 6 of the deployment guide names every deployed IT class of imap-mcp-it, so the guide follows the suite. */
    @Test
    void deploymentGuideSectionSixNamesEveryDeployedItClass() throws IOException {
        String text = read(Path.of("..", "doc", "DEPLOYMENT.md"));
        int from = text.indexOf("## 6.");
        int to = text.indexOf("## 7.");
        assertThat(from).isGreaterThanOrEqualTo(0);
        assertThat(to).isGreaterThan(from);
        String section = text.substring(from, to);
        Path it = Path.of("..", "imap-mcp-it", "src", "test", "java", "de", "alexanderlindhorst", "imapmcp", "it");
        List<String> classes;
        try (Stream<Path> s = Files.list(it)) {
            classes = s.map(p -> p.getFileName().toString()).filter(n -> n.endsWith("DeployedIT.java"))
                    .map(n -> n.substring(0, n.length() - ".java".length())).sorted().toList();
        }
        assertThat(classes).isNotEmpty();
        for (String c : classes) {
            assertThat(section).as("section 6 names " + c).contains(c);
        }
    }

    private static String deploymentSectionSix() throws IOException {
        String text = read(Path.of("..", "doc", "DEPLOYMENT.md"));
        int from = text.indexOf("## 6.");
        int to = text.indexOf("## 7.");
        assertThat(from).isGreaterThanOrEqualTo(0);
        assertThat(to).isGreaterThan(from);
        return text.substring(from, to);
    }

    @Test
    void deploymentGuideSectionSixNamesTheNativeDockerCiRun() throws IOException {
        String section = deploymentSectionSix();
        assertThat(section).contains("native Docker").contains("ubuntu-24.04").contains("48c29d1")
                .contains("2026-11-12").contains("43304462127").contains("3270 tests").contains("0 skipped")
                .contains("48 deployed-IT tests in 8 classes").contains("not recorded");
    }

    @Test
    void deploymentGuideNotVerifiedNoLongerListsNativeDockerOrCiRunner() throws IOException {
        String section = deploymentSectionSix();
        String notVerified = section.substring(section.indexOf("Not verified"));
        assertThat(notVerified).doesNotContain("native Docker").doesNotContain("CI runner")
                .contains("Claude connector").contains("Let's Encrypt").contains("TOTP");
    }
}
