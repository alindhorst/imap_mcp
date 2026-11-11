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

    /** Exit code and stdout of a command run inside a service container; output is never logged by the fixture. */
    record ExecResult(long exitCode, String stdout) {
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
                client().execStartCmd(id).exec(new ResultCallback.Adapter<Frame>() {
                    @Override
                    public void onNext(Frame frame) {
                        if (frame.getStreamType() == com.github.dockerjava.api.model.StreamType.STDOUT) {
                            out.writeBytes(frame.getPayload());
                        }
                    }
                }).awaitCompletion(120, TimeUnit.SECONDS);
                Long code = client().inspectExecCmd(id).exec().getExitCodeLong();
                return new ExecResult(code == null ? -1 : code, out.toString(java.nio.charset.StandardCharsets.UTF_8));
            }
        }
        throw new IllegalStateException("no running container for service " + service);
    }

    Path logsDir() {
        return dir.resolve("logs");
    }

    /** Saves the log of every container of the project, running or not, as {@code <service>.log}. */
    void saveLogs() throws IOException {
        Files.createDirectories(logsDir());
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
