package de.alexanderlindhorst.imapmcp.it;

import static org.assertj.core.api.Assertions.assertThat;

import com.github.dockerjava.api.command.InspectContainerResponse;
import com.github.dockerjava.api.model.PortBinding;
import com.github.dockerjava.api.model.Ports;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.Network;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.images.builder.ImageFromDockerfile;
import org.testcontainers.utility.MountableFile;

/**
 * The http-local image (#50) built from the real Dockerfile and the shaded jar, run next to Dovecot. The config files
 * are copied into /config (not bind-mounted: the container user 10001 must be able to create /config/state, and a
 * bind-mounted host directory would be owned by the host user and could not be cleaned up afterwards).
 */
class DockerImageIT {

    private static final Path DOCKERFILE = Path.of("..", "imap-mcp-app", "src", "main", "docker", "Dockerfile")
            .toAbsolutePath().normalize();
    private static final String TOOLS_LIST = "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/list\",\"params\":{}}";
    private static final List<String> TOOLS =
            List.of("list_accounts", "list_folders", "search_messages", "get_message", "list_attachments");

    private static Network network;
    private static DovecotContainer dovecot;
    private static ImageFromDockerfile image;
    private static GenericContainer<?> app;
    private static String token;
    private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

    @TempDir
    static Path dir;

    @BeforeAll
    static void start() throws Exception {
        network = Network.newNetwork();
        dovecot = new DovecotContainer().withNetwork(network);
        dovecot.start();

        Path jar = Path.of(System.getProperty("imapmcp.jar"));
        assertThat(jar).exists();
        image = new ImageFromDockerfile("imap-mcp-it", true)
                .withFileFromPath("Dockerfile", DOCKERFILE)
                .withFileFromPath("imap-mcp.jar", jar)
                .withBuildArg("JAR_FILE", "imap-mcp.jar");

        Path cfgDir = Files.createDirectories(dir.resolve("cfg"));
        Path config = cfgDir.resolve("config.yaml");
        Files.writeString(config, """
                accounts:
                  - id: work
                    host: %s
                    port: %d
                    username: alice
                    password: "file:/config/imap_password"
                """.formatted(DovecotContainer.NETWORK_ALIAS, DovecotContainer.IMAPS_PORT));
        Path password = cfgDir.resolve("imap_password");
        Files.writeString(password, DovecotContainer.PASSWORD, StandardCharsets.UTF_8);
        Path trust = TestTrust.writeTruststore(cfgDir);

        app = new GenericContainer<>(image)
                .withNetwork(network)
                .withExposedPorts(8080)
                // Publish on host loopback only, as documented in the README.
                .withCreateContainerCmdModifier(cmd -> cmd.getHostConfig().withPortBindings(
                        new PortBinding(Ports.Binding.bindIp("127.0.0.1"), com.github.dockerjava.api.model.ExposedPort.tcp(8080))))
                // One directory copy: separate withCopyFileToContainer calls into /config lost all but the first file.
                .withCopyFileToContainer(MountableFile.forHostPath(cfgDir, 0777), "/config")
                .withTmpFs(Map.of("/logs", "rw,mode=1777"))
                .withEnv("JAVA_TOOL_OPTIONS", "-Djavax.net.ssl.trustStore=/config/dovecot-truststore.p12 -Djavax.net.ssl.trustStorePassword="
                        + TestTrust.PASSWORD + " -Djavax.net.ssl.trustStoreType=PKCS12")
                .waitingFor(Wait.forHealthcheck().withStartupTimeout(Duration.ofSeconds(60)));
        app.start();
        token = app.execInContainer("cat", "/config/state/http-token").getStdout().strip();
    }

    @AfterAll
    static void stop() {
        if (app != null) {
            app.stop();
        }
        if (dovecot != null) {
            dovecot.stop();
        }
        if (network != null) {
            network.close();
        }
    }

    private static URI uri(String path) {
        return URI.create("http://127.0.0.1:" + app.getMappedPort(8080) + path);
    }

    /** AC1: Docker health status healthy (the wait strategy already required it within 60 s). */
    @Test
    void dockerHealthStatusIsHealthy() {
        var health = app.getCurrentContainerInfo().getState().getHealth();
        assertThat(health).isNotNull();
        assertThat(health.getStatus()).isEqualTo("healthy");
    }

    /** AC2 */
    @Test
    void runsAsUid10001() throws Exception {
        assertThat(app.execInContainer("id", "-u").getStdout().strip()).isEqualTo("10001");
        assertThat(app.getCurrentContainerInfo().getConfig().getUser()).isEqualTo("10001");
    }

    /** AC3 */
    @Test
    void tokenFileIsPrivateAndTokenIsNotLogged() throws Exception {
        var stat = app.execInContainer("stat", "-c", "%a %u %g", "/config/state/http-token");
        assertThat(stat.getStdout().strip()).isEqualTo("600 10001 10001");
        assertThat(token).hasSizeGreaterThanOrEqualTo(32);
        assertThat(app.getLogs()).doesNotContain(token);
        assertThat(app.execInContainer("cat", "/logs/imap-mcp.log").getStdout()).doesNotContain(token);
    }

    /** AC4 */
    @Test
    void toolsListWithTokenWithoutTokenAndHealthFromHost() throws Exception {
        HttpResponse<String> ok = post(token);
        assertThat(ok.statusCode()).isEqualTo(200);
        for (String tool : TOOLS) {
            assertThat(ok.body()).contains("\"" + tool + "\"");
        }
        assertThat(ok.body().split("\"inputSchema\"", -1)).as("five tools").hasSize(6);

        assertThat(post(null).statusCode()).isEqualTo(401);
        assertThat(post("not-the-token").statusCode()).isEqualTo(401);

        HttpResponse<String> health = HTTP.send(HttpRequest.newBuilder(uri("/health")).GET().build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(health.statusCode()).isEqualTo(200);
    }

    /** Loopback publish: the host side of the port mapping is 127.0.0.1 only. */
    @Test
    void portIsPublishedOnHostLoopbackOnly() {
        InspectContainerResponse info = app.getCurrentContainerInfo();
        Ports.Binding[] bindings = info.getNetworkSettings().getPorts().getBindings()
                .get(com.github.dockerjava.api.model.ExposedPort.tcp(8080));
        assertThat(bindings).hasSize(1);
        assertThat(bindings[0].getHostIp()).isEqualTo("127.0.0.1");
    }

    /** AC5: a fresh container of the image, without volumes, has an empty /config and only the jar in /app. */
    @Test
    void freshContainerHasNoConfigStateOrCredentials() throws Exception {
        try (GenericContainer<?> fresh = new GenericContainer<>(image)
                .withCreateContainerCmdModifier(cmd -> cmd.withEntrypoint("sleep", "120"))
                .withCommand("120")
                .waitingFor(Wait.forSuccessfulCommand("true"))) {
            fresh.start();
            assertThat(fresh.execInContainer("ls", "-A", "/config").getStdout()).isEmpty();
            assertThat(fresh.execInContainer("ls", "-A", "/logs").getStdout()).isEmpty();
            assertThat(fresh.execInContainer("ls", "-A", "/app").getStdout().strip()).isEqualTo("imap-mcp.jar");
        }
    }

    /** AC6: no secret passed as build arg or ENV, none in the layer commands. */
    @Test
    void imageMetadataHoldsNoPassword() {
        String imageId = app.getCurrentContainerInfo().getImageId();
        var dockerClient = app.getDockerClient();
        var inspect = dockerClient.inspectImageCmd(imageId).exec();
        String env = String.join("\n", inspect.getConfig().getEnv());
        assertThat(env).doesNotContain(DovecotContainer.PASSWORD).doesNotContain("PASSWORD");
        // Build args end up in the image metadata (history); the Dockerfile only declares JAR_FILE.
        String meta = inspect.getConfig().toString() + inspect.getContainerConfig();
        assertThat(meta).doesNotContain(DovecotContainer.PASSWORD);
        // The truststore/password options are runtime-only container env, not part of the image.
        assertThat(env).doesNotContain("JAVA_TOOL_OPTIONS");
    }

    private static HttpResponse<String> post(String bearer) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(uri("/mcp"))
                .header("Content-Type", "application/json").header("Accept", "application/json, text/event-stream")
                .POST(HttpRequest.BodyPublishers.ofString(TOOLS_LIST));
        if (bearer != null) {
            b.header("Authorization", "Bearer " + bearer);
        }
        return HTTP.send(b.build(), HttpResponse.BodyHandlers.ofString());
    }
}
