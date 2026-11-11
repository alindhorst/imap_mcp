package de.alexanderlindhorst.imapmcp.it;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DeployedRunCopyTest {

    @TempDir
    Path dir;

    private Path source() throws Exception {
        Path src = Files.createDirectories(dir.resolve("src"));
        Files.writeString(src.resolve("compose.yaml"), "services: {}");
        Files.createDirectories(src.resolve("caddy/local"));
        Files.writeString(src.resolve("caddy/local/Caddyfile"), "x");
        // generated state of an owner's earlier setup: must never reach the copy
        Files.writeString(src.resolve(".env"), "SECRET=owner");
        Files.createDirectories(src.resolve("local-ca"));
        Files.writeString(src.resolve("local-ca/ca.key"), "key");
        Files.createDirectories(src.resolve("app"));
        Files.writeString(src.resolve("app/config.yaml"), "owner: true");
        return src;
    }

    @Test
    void copiesTheShippedFilesButNoGeneratedState() throws Exception {
        Path dst = dir.resolve("dst");
        DeployedRunCopy.copy(source(), dst);
        assertThat(dst.resolve("compose.yaml")).exists();
        assertThat(dst.resolve("caddy/local/Caddyfile")).exists();
        assertThat(dst.resolve(".env")).doesNotExist();
        assertThat(dst.resolve("local-ca")).doesNotExist();
        assertThat(dst.resolve("app/config.yaml")).doesNotExist();
    }

    @Test
    void snapshotChangesWhenTheTreeIsTouched() throws Exception {
        Path src = source();
        Map<String, String> before = DeployedRunCopy.snapshot(src);
        assertThat(DeployedRunCopy.snapshot(src)).isEqualTo(before);
        Files.writeString(src.resolve("new-file"), "x");
        assertThat(DeployedRunCopy.snapshot(src)).isNotEqualTo(before);
    }
}
