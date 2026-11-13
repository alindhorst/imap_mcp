package de.alexanderlindhorst.imapmcp.it;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.async.ResultCallback;
import com.github.dockerjava.api.command.ListContainersCmd;
import com.github.dockerjava.api.model.Container;
import com.github.dockerjava.api.model.ContainerPort;
import com.github.dockerjava.api.model.Frame;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.ComposeContainer;
import org.testcontainers.containers.wait.strategy.Wait;

/**
 * One compose project under Testcontainers ({@code ComposeContainer}, which runs the local {@code docker compose}
 * against the engine Testcontainers selected): started with a bound, compose logs saved under {@code <dir>/logs}
 * when the start fails, torn down with {@code down -v}. The project name is the caller's unique one, so concurrent
 * stacks never collide, and Testcontainers registers the project label at Ryuk, the backstop when the JVM dies.
 * Ports are read from the engine by project label: the socat ambassador of {@code withExposedService} does not work
 * on rootless Podman ("link is not supported").
 */
final class DeployedCompose {

    static final String PROJECT_LABEL = "com.docker.compose.project";
    static final String SERVICE_LABEL = "com.docker.compose.service";

    private final String project;
    private final Path dir;
    private final ComposeContainer container;
    private final List<FileItem> collected = new ArrayList<>();

    private record FileItem(String service, String path) {
    }

    DeployedCompose(String project, Path dir, List<String> files) {
        this.project = project;
        this.dir = dir;
        this.container = new ComposeContainer(project, files.stream().map(f -> dir.resolve(f).toFile()).toList())
                .withPull(false)
                .withRemoveVolumes(true);
    }

    String project() {
        return project;
    }

    DeployedCompose waitingForHealthy(String service) {
        container.waitingFor(service, Wait.forHealthcheck());
        return this;
    }

    /**
     * Registers a path of a service container whose files {@link #saveLogs} copies out (engine archive copy, which
     * also works on an exited container) below {@code logs/<service>-files/}.
     */
    DeployedCompose collectingFiles(String service, String containerPath) {
        collected.add(new FileItem(service, containerPath));
        return this;
    }

    /** Starts the project; any failure (also the bound) saves the logs and is rethrown as IllegalStateException. */
    void start(Duration bound) {
        container.withStartupTimeout(bound);
        CompletableFuture<Void> started = CompletableFuture.runAsync(container::start);
        try {
            started.get(bound.toMillis(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            saveLogsQuietly();
            throw new IllegalStateException("compose project " + project + " did not start within " + bound, e);
        } catch (Exception e) {
            saveLogsQuietly();
            throw new IllegalStateException("compose project " + project + " failed to start: "
                    + rootMessage(e), e);
        }
    }

    private static String rootMessage(Throwable t) {
        Throwable x = t;
        while (x.getCause() != null) {
            x = x.getCause();
        }
        return x.getMessage();
    }

    /** {@code down -v} of the project; call it in a finally block, also after a failed start. */
    void stop() {
        container.stop();
    }

    int hostPort(String service, int containerPort) {
        for (Container c : projectContainers(false)) {
            if (!service.equals(c.getLabels().get(SERVICE_LABEL))) {
                continue;
            }
            for (ContainerPort p : c.getPorts()) {
                if (p.getPrivatePort() != null && p.getPrivatePort() == containerPort && p.getPublicPort() != null) {
                    return p.getPublicPort();
                }
            }
        }
        throw new IllegalStateException("no published port " + containerPort + " on " + service);
    }

    /**
     * Testcontainers appends six random characters to the identifier it is given, so the real compose project is
     * {@code <identifier><6 chars>}; the identifier itself carries a random part and is unique per stack.
     */
    private boolean isMine(java.util.Map<String, String> labels) {
        String p = labels == null ? null : labels.get(PROJECT_LABEL);
        return p != null && p.startsWith(project) && p.length() == project.length() + 6;
    }

    List<Container> projectContainers(boolean all) {
        ListContainersCmd cmd = client().listContainersCmd().withShowAll(all);
        return cmd.exec().stream().filter(c -> isMine(c.getLabels())).toList();
    }

    /** Containers, networks and volumes of the project that still exist (descriptions without any content). */
    List<String> leftovers() {
        DockerClient docker = client();
        List<String> result = new ArrayList<>();
        projectContainers(true).forEach(c -> result.add("container " + c.getNames()[0]));
        docker.listNetworksCmd().exec().stream().filter(n -> isMine(n.getLabels()))
                .forEach(n -> result.add("network " + n.getName()));
        docker.listVolumesCmd().exec().getVolumes().stream().filter(v -> isMine(v.getLabels()))
                .forEach(v -> result.add("volume " + v.getName()));
        return result;
    }

    /** Restarts the service's container and waits, up to {@code bound}, until its healthcheck reports healthy. */
    void restart(String service, java.time.Duration bound) throws InterruptedException {
        Container c = projectContainers(false).stream().filter(x -> service.equals(x.getLabels().get(SERVICE_LABEL)))
                .findFirst().orElseThrow(() -> new IllegalStateException("no running container for " + service));
        client().restartContainerCmd(c.getId()).withTimeout(30).exec();
        long deadline = System.nanoTime() + bound.toNanos();
        while (System.nanoTime() < deadline) {
            var health = client().inspectContainerCmd(c.getId()).exec().getState().getHealth();
            if (health != null && "healthy".equals(health.getStatus())) {
                return;
            }
            Thread.sleep(1000);
        }
        throw new IllegalStateException(service + " was not healthy within " + bound + " after the restart");
    }

    /** The compose project name as the engine knows it (the identifier plus Testcontainers' random suffix). */
    String actualProject() {
        return projectContainers(true).stream().map(c -> c.getLabels().get(PROJECT_LABEL)).findFirst()
                .orElseThrow(() -> new IllegalStateException("no container of " + project));
    }

    /** The project network with the given compose name (as in compose.yaml), inspected; null if it was not created. */
    com.github.dockerjava.api.model.Network network(String name) {
        return client().listNetworksCmd().exec().stream()
                .filter(n -> isMine(n.getLabels()) && name.equals(n.getLabels().get("com.docker.compose.network")))
                .findFirst().map(n -> client().inspectNetworkCmd().withNetworkId(n.getId()).exec()).orElse(null);
    }

    /** IPv4 addresses of the project's running containers on the given project network. */
    List<String> networkIps(String name) {
        com.github.dockerjava.api.model.Network net = network(name);
        List<String> ips = new ArrayList<>();
        for (Container c : projectContainers(false)) {
            var settings = client().inspectContainerCmd(c.getId()).exec().getNetworkSettings().getNetworks();
            var entry = settings.get(net.getName());
            if (entry != null && entry.getIpAddress() != null && !entry.getIpAddress().isEmpty()) {
                ips.add(entry.getIpAddress());
            }
        }
        return ips;
    }

    /** One environment variable of a running service container; null if unset. */
    String env(String service, String variable) {
        for (Container c : projectContainers(false)) {
            if (service.equals(c.getLabels().get(SERVICE_LABEL))) {
                String[] env = client().inspectContainerCmd(c.getId()).exec().getConfig().getEnv();
                for (String e : env == null ? new String[0] : env) {
                    if (e.startsWith(variable + "=")) {
                        return e.substring(variable.length() + 1);
                    }
                }
            }
        }
        return null;
    }

    /** Exit code, stdout and stderr (for classification only) of a command run inside a service container; output is never logged by the fixture. */
    record ExecResult(long exitCode, String stdout, String stderr) {
        @Override
        public String toString() {
            return "exit " + exitCode;
        }
    }

    /**
     * Runs a command in a running service container. {@code env} entries ({@code NAME=value}) are passed to the exec
     * only, so a secret never has to be part of the command line.
     */
    ExecResult exec(String service, List<String> env, String... command) throws InterruptedException {
        for (Container c : projectContainers(false)) {
            if (service.equals(c.getLabels().get(SERVICE_LABEL))) {
                String id = client().execCreateCmd(c.getId()).withAttachStdout(true).withAttachStderr(true)
                        .withEnv(env).withCmd(command).exec().getId();
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                ByteArrayOutputStream err = new ByteArrayOutputStream();
                client().execStartCmd(id).exec(new ResultCallback.Adapter<Frame>() {
                    @Override
                    public void onNext(Frame frame) {
                        if (frame.getStreamType() == com.github.dockerjava.api.model.StreamType.STDOUT) {
                            out.writeBytes(frame.getPayload());
                        } else if (frame.getStreamType() == com.github.dockerjava.api.model.StreamType.STDERR) {
                            err.writeBytes(frame.getPayload());
                        }
                    }
                }).awaitCompletion(120, TimeUnit.SECONDS);
                Long code = client().inspectExecCmd(id).exec().getExitCodeLong();
                return new ExecResult(code == null ? -1 : code, out.toString(java.nio.charset.StandardCharsets.UTF_8),
                        err.toString(java.nio.charset.StandardCharsets.UTF_8));
            }
        }
        throw new IllegalStateException("no running container for service " + service);
    }

    Path logsDir() {
        return dir.resolve("logs");
    }

    private static final long FILE_CAP = 4L << 20;
    private static final long ITEM_CAP = 16L << 20;

    /**
     * Saves the log of every container of the project, running or not, as {@code <service>.log}, the registered
     * files below {@code <service>-files/} and {@code containers.txt} (see {@link ContainerFacts}). A failure on one
     * item never stops the others; containers.txt notes it without any exception text.
     */
    void saveLogs() throws IOException {
        Files.createDirectories(logsDir());
        List<String> lines = new ArrayList<>();
        for (Container c : projectContainers(true)) {
            String service = c.getLabels().getOrDefault(SERVICE_LABEL, c.getId().substring(0, 12));
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            try {
                client().logContainerCmd(c.getId()).withStdOut(true).withStdErr(true).exec(
                        new ResultCallback.Adapter<Frame>() {
                            @Override
                            public void onNext(Frame frame) {
                                out.writeBytes(frame.getPayload());
                            }
                        }).awaitCompletion(30, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            Files.write(logsDir().resolve(service + ".log"), out.toByteArray());
            lines.add(factsOf(c, service).line());
        }
        for (FileItem item : collected) {
            if (!collectFiles(item)) {
                lines.add("note: files of " + item.service() + " " + item.path() + " not collected");
            }
        }
        Files.write(logsDir().resolve("containers.txt"), lines);
    }

    private ContainerFacts factsOf(Container c, String service) {
        try {
            var state = client().inspectContainerCmd(c.getId()).exec().getState();
            var health = state.getHealth();
            return new ContainerFacts(service, state.getStatus(), c.getStatus(),
                    health == null ? null : health.getStatus(), state.getExitCodeLong() == null ? -1
                            : state.getExitCodeLong());
        } catch (RuntimeException e) {
            return new ContainerFacts(service, c.getState(), c.getStatus(), null, -1);
        }
    }

    private boolean collectFiles(FileItem item) {
        try {
            Container c = projectContainers(true).stream()
                    .filter(x -> item.service().equals(x.getLabels().get(SERVICE_LABEL))).findFirst().orElse(null);
            if (c == null) {
                return false;
            }
            try (java.io.InputStream tar = client().copyArchiveFromContainerCmd(c.getId(), item.path()).exec()) {
                TarExtractor.extract(tar, logsDir().resolve(item.service() + "-files"), FILE_CAP, ITEM_CAP);
            }
            return true;
        } catch (IOException | RuntimeException e) {
            return false;
        }
    }

    private void saveLogsQuietly() {
        try {
            saveLogs();
        } catch (IOException | RuntimeException e) {
            // the start failure is the finding; a failure to save logs must not hide it
        }
    }

    private static DockerClient client() {
        return DockerClientFactory.lazyClient();
    }
}
