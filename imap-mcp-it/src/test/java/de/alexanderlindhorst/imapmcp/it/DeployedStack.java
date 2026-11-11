package de.alexanderlindhorst.imapmcp.it;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.exception.DockerException;
import com.github.dockerjava.api.model.Network;
import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.InetSocketAddress;
import java.net.ProxySelector;
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.KeyStore;
import java.security.cert.CertificateFactory;
import java.time.Duration;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManagerFactory;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.images.builder.ImageFromDockerfile;

/**
 * The shipped dist/docker stack under Testcontainers, one per JVM (shared by all {@code *DeployedIT} classes): a copy
 * prepared by setup.sh --local, the test override, the app image built from the shipped Dockerfile, and the test JVM
 * reaching Caddy only through the forward proxy. A failed start throws (never skips) and leaves the compose logs
 * under {@code target/it-compose/<run>/logs}. A test that mutates the stack must restore it.
 *
 * <p>Concurrency: the compose project name, the run directory and the app image tag carry a random run id; no host
 * port and no container name is fixed; a backend subnet that another stack holds makes the start move on to the next
 * candidate of {@link DeployedSubnets}.
 */
final class DeployedStack {

    private static final Duration START_BOUND = Duration.ofMinutes(8);
    private static final Pattern ENV_LINE = Pattern.compile("^([A-Z0-9_]+)=(.*)$");

    private static DeployedStack instance;
    private static Throwable failure;

    private final Path sourceDir;
    private final Map<String, String> sourceSnapshot;
    private final Path runDir;
    private final String subnet;
    private final String imageTag;
    private final DeployedCompose compose;
    private final StringBuilder captured;
    private final List<String> buildContext;
    private HttpClient http;
    private int proxyPort;

    private DeployedStack(Path sourceDir, Map<String, String> sourceSnapshot, Path runDir, String subnet,
            String imageTag, DeployedCompose compose, StringBuilder captured, List<String> buildContext) {
        this.sourceDir = sourceDir;
        this.sourceSnapshot = sourceSnapshot;
        this.runDir = runDir;
        this.subnet = subnet;
        this.imageTag = imageTag;
        this.compose = compose;
        this.captured = captured;
        this.buildContext = buildContext;
    }

    static synchronized DeployedStack instance() {
        if (instance != null) {
            return instance;
        }
        if (failure != null) {
            throw new IllegalStateException("the deployed stack failed to start earlier in this run", failure);
        }
        try {
            instance = startFirstFreeSubnet();
        } catch (Throwable t) {
            failure = t;
            throw new IllegalStateException("the deployed stack failed to start: " + t.getMessage(), t);
        }
        DeployedStack started = instance;
        Runtime.getRuntime().addShutdownHook(new Thread(started::stop, "deployed-stack-teardown"));
        return instance;
    }

    // ---- start ---------------------------------------------------------------------------------------------

    private static DeployedStack startFirstFreeSubnet() throws Exception {
        Path source = Path.of("..", "dist", "docker").toAbsolutePath().normalize();
        Map<String, String> snapshot = DeployedRunCopy.snapshot(source);
        Path jar = Path.of(System.getProperty("imapmcp.jar")).toAbsolutePath();
        List<String> subnets = DeployedSubnets.candidates(System.getProperty(DeployedSubnets.PROPERTY));
        subnets.forEach(DeployedSubnets::requireUsable);
        for (String subnet : subnets) {
            if (subnetBusy(subnet)) {
                continue;
            }
            DeployedStack stack = tryStart(source, snapshot, jar, subnet);
            if (stack != null) {
                return stack;
            }
        }
        throw new IllegalStateException("every candidate backend subnet is in use: " + subnets);
    }

    /** Creates and removes a network with the subnet: the engine's own answer on whether it is free. */
    private static boolean subnetBusy(String subnet) {
        DockerClient docker = DockerClientFactory.lazyClient();
        String name = "imcp-probe-" + HexFormat.of().formatHex(UUID.randomUUID().toString().getBytes()).substring(0, 8);
        try {
            String id = docker.createNetworkCmd().withName(name).withInternal(true)
                    .withIpam(new com.github.dockerjava.api.model.Network.Ipam().withConfig(
                            new com.github.dockerjava.api.model.Network.Ipam.Config().withSubnet(subnet))).exec().getId();
            docker.removeNetworkCmd(id).exec();
            return false;
        } catch (DockerException e) {
            if (DeployedSubnets.isOverlap(e.getMessage())) {
                return true;
            }
            throw e;
        }
    }

    /** Starts the stack on the subnet; null when the subnet was taken by another stack meanwhile. */
    private static DeployedStack tryStart(Path source, Map<String, String> snapshot, Path jar, String subnet)
            throws Exception {
        String run = HexFormat.of().formatHex(UUID.randomUUID().toString().getBytes()).substring(0, 8);
        Path runDir = Path.of("target", "it-compose", run).toAbsolutePath();
        DeployedRunCopy.copy(source, runDir);
        StringBuilder captured = new StringBuilder();
        runSetup(runDir, subnet, captured);
        Files.writeString(runDir.resolve(".env"), "COMPOSE_PROFILES=proxy\n", StandardOpenOption.APPEND);
        Files.writeString(runDir.resolve("squid.conf"), DeployedSquidConf.render());

        String imageTag = "localhost/imap-mcp-it-deployed:" + run;
        List<String> context = List.of("Dockerfile", "jar/imap-mcp.jar");
        new ImageFromDockerfile(imageTag, true)
                .withFileFromPath("Dockerfile", runDir.resolve("Dockerfile"))
                .withFileFromPath("jar/imap-mcp.jar", jar)
                .get();
        Files.writeString(runDir.resolve("it-compose.override.yaml"), DeployedOverride.render(imageTag, ItImages.SQUID, "x"));

        DeployedCompose compose = new DeployedCompose("imcp-" + run, runDir,
                List.of("compose.yaml", "it-compose.override.yaml")).waitingForHealthy("app");
        DeployedStack stack = new DeployedStack(source, snapshot, runDir, subnet, imageTag, compose, captured, context);
        try {
            compose.start(START_BOUND);
        } catch (RuntimeException e) {
            boolean lostTheSubnet = compose.network("backend") == null && subnetBusy(subnet);
            stack.stop();
            if (lostTheSubnet) {
                return null;
            }
            throw e;
        }
        try {
            stack.proxyPort = compose.hostPort("proxy", 3128);
            stack.http = stack.buildClient();
            stack.awaitProxy();
        } catch (Exception e) {
            compose.saveLogs();
            stack.stop();
            throw e;
        }
        return stack;
    }

    private static void runSetup(Path runDir, String subnet, StringBuilder captured) throws Exception {
        ProcessBuilder pb = new ProcessBuilder("bash", "setup.sh", "--local").directory(runDir.toFile())
                .redirectErrorStream(true);
        pb.environment().put("IMAP_HOSTS", "imap.imap-mcp.test");
        pb.environment().put("BACKEND_SUBNET", subnet);
        // the truststore step of setup.sh runs a container: on the engine Testcontainers selected
        pb.environment().put("ENGINE", "docker");
        pb.environment().put("DOCKER_HOST", DockerClientFactory.instance().getTransportConfig().getDockerHost().toString());
        Process process = pb.start();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        int code = process.waitFor();
        captured.append(output);
        if (code != 0) {
            throw new IllegalStateException("setup.sh --local failed with exit code " + code + ": " + output);
        }
    }

    private HttpClient buildClient() throws Exception {
        CertificateFactory cf = CertificateFactory.getInstance("X.509");
        KeyStore trust = KeyStore.getInstance(KeyStore.getDefaultType());
        trust.load(null, null);
        // trust only the root of the copy's local CA, nothing else
        trust.setCertificateEntry("local-ca", cf.generateCertificate(
                new ByteArrayInputStream(Files.readAllBytes(runDir.resolve("local-ca/ca.crt")))));
        TrustManagerFactory tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        tmf.init(trust);
        SSLContext ssl = SSLContext.getInstance("TLS");
        ssl.init(null, tmf.getTrustManagers(), null);
        return HttpClient.newBuilder().sslContext(ssl).version(HttpClient.Version.HTTP_1_1)
                .proxy(ProxySelector.of(new InetSocketAddress("127.0.0.1", proxyPort)))
                .followRedirects(HttpClient.Redirect.NEVER).connectTimeout(Duration.ofSeconds(10)).build();
    }

    private void awaitProxy() throws Exception {
        long deadline = System.nanoTime() + Duration.ofSeconds(90).toNanos();
        while (true) {
            try {
                if (proxyConnect(new java.net.URI(mcpOrigin()).getAuthority()).contains(" 200")) {
                    return;
                }
            } catch (IOException e) {
                // proxy not listening yet
            }
            if (System.nanoTime() > deadline) {
                throw new IllegalStateException("the forward proxy does not tunnel to Caddy after 90 s");
            }
            Thread.sleep(1000);
        }
    }

    // ---- teardown ------------------------------------------------------------------------------------------

    private void stop() {
        try {
            compose.stop();
        } catch (RuntimeException e) {
            // Ryuk is the backstop
        }
        try {
            DockerClientFactory.lazyClient().removeImageCmd(imageTag).withForce(true).exec();
        } catch (RuntimeException e) {
            // already gone, or removed by Ryuk
        }
    }

    // ---- accessors for the tests ---------------------------------------------------------------------------

    Path sourceDir() {
        return sourceDir;
    }

    Map<String, String> sourceSnapshotAtStart() {
        return sourceSnapshot;
    }

    Path runDir() {
        return runDir;
    }

    String mcpOrigin() {
        return envValue("MCP_ORIGIN");
    }

    String idpOrigin() {
        return envValue("IDP_ORIGIN");
    }

    private String envValue(String name) {
        try {
            for (String line : Files.readAllLines(runDir.resolve(".env"))) {
                Matcher m = ENV_LINE.matcher(line);
                if (m.matches() && m.group(1).equals(name)) {
                    return m.group(2);
                }
            }
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
        throw new IllegalStateException(name + " is not in the copy's .env");
    }

    /** A value of the copy's rendered app/config.yaml, e.g. {@code configValue("oauth", "issuer")}. */
    String configValue(String section, String key) {
        try {
            boolean inSection = false;
            for (String line : Files.readAllLines(runDir.resolve("app/config.yaml"))) {
                if (!line.startsWith(" ") && !line.startsWith("#") && !line.isBlank()) {
                    inSection = line.startsWith(section + ":");
                } else if (inSection) {
                    Matcher m = Pattern.compile("^\\s+" + Pattern.quote(key) + ":\\s*(.*?)\\s*$").matcher(line);
                    if (m.matches()) {
                        return m.group(1).replaceAll("^\"|\"$", "");
                    }
                }
            }
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
        throw new IllegalStateException(section + "." + key + " is not in the rendered config");
    }

    HttpResponse<String> get(String url) throws IOException, InterruptedException {
        return http.send(HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(30)).GET().build(),
                HttpResponse.BodyHandlers.ofString());
    }

    HttpResponse<String> postJson(String url, String body) throws IOException, InterruptedException {
        return http.send(HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(30))
                .header("Content-Type", "application/json").header("Accept", "application/json, text/event-stream")
                .POST(HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString());
    }

    /** Status line the forward proxy answers to a raw {@code CONNECT <authority>}. */
    String proxyConnect(String authority) throws IOException {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress("127.0.0.1", proxyPort), 5000);
            socket.setSoTimeout(15000);
            socket.getOutputStream().write(("CONNECT " + authority + " HTTP/1.1\r\nHost: " + authority + "\r\n\r\n")
                    .getBytes(StandardCharsets.US_ASCII));
            socket.getOutputStream().flush();
            return new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.US_ASCII))
                    .readLine();
        }
    }

    String backendSubnet() {
        return subnet;
    }

    Network backendNetwork() {
        return compose.network("backend");
    }

    /** IPv4 addresses of every container on the backend network. */
    List<String> backendContainerIps() {
        return compose.networkIps("backend");
    }

    /** Value of an environment variable inside a running service container (compared, never printed). */
    String containerEnv(String service, String name) {
        return compose.env(service, name);
    }

    /** Everything the fixture captured of setup.sh, for the canary scan. */
    String capturedOutput() {
        return captured.toString();
    }

    List<String> buildContextEntries() {
        return buildContext;
    }

    void saveLogs() throws IOException {
        compose.saveLogs();
    }
}
