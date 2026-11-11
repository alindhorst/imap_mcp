package de.alexanderlindhorst.imapmcp.it;

import com.github.dockerjava.api.model.Network;
import java.io.IOException;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/**
 * The shipped dist/docker stack under Testcontainers, one per JVM (shared by all {@code *DeployedIT} classes): a copy
 * prepared by setup.sh --local, the test override, the app image built from the shipped Dockerfile, and the test JVM
 * reaching Caddy only through the forward proxy. A failed start throws (never skips).
 */
final class DeployedStack {

    static DeployedStack instance() {
        throw new UnsupportedOperationException();
    }

    Path sourceDir() {
        throw new UnsupportedOperationException();
    }

    Map<String, String> sourceSnapshotAtStart() {
        throw new UnsupportedOperationException();
    }

    Path runDir() {
        throw new UnsupportedOperationException();
    }

    String mcpOrigin() {
        throw new UnsupportedOperationException();
    }

    String idpOrigin() {
        throw new UnsupportedOperationException();
    }

    /** A value of the copy's rendered app/config.yaml, e.g. {@code configValue("oauth", "issuer")}. */
    String configValue(String section, String key) {
        throw new UnsupportedOperationException();
    }

    HttpResponse<String> get(String url) throws IOException, InterruptedException {
        throw new UnsupportedOperationException();
    }

    HttpResponse<String> postJson(String url, String body) throws IOException, InterruptedException {
        throw new UnsupportedOperationException();
    }

    /** Status line the forward proxy answers to a raw {@code CONNECT <authority>}. */
    String proxyConnect(String authority) throws IOException {
        throw new UnsupportedOperationException();
    }

    String backendSubnet() {
        throw new UnsupportedOperationException();
    }

    Network backendNetwork() {
        throw new UnsupportedOperationException();
    }

    /** IPv4 addresses of every container on the backend network. */
    List<String> backendContainerIps() {
        throw new UnsupportedOperationException();
    }

    /** Value of an environment variable inside a running service container (compared, never printed). */
    String containerEnv(String service, String name) {
        throw new UnsupportedOperationException();
    }

    /** Everything the fixture captured of setup.sh and the compose logs, for the canary scan. */
    String capturedOutput() {
        throw new UnsupportedOperationException();
    }

    List<String> buildContextEntries() {
        throw new UnsupportedOperationException();
    }

    void saveLogs() throws IOException {
        throw new UnsupportedOperationException();
    }
}
