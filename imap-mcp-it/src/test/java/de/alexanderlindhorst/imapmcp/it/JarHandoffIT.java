package de.alexanderlindhorst.imapmcp.it;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.jar.JarFile;
import org.junit.jupiter.api.Test;

/** The shaded jar built by imap-mcp-app is handed to the ITs through the system property {@code imapmcp.jar}. */
class JarHandoffIT {

    @Test
    void imapmcpJarPropertyPointsToTheShadedJar() throws Exception {
        String p = System.getProperty("imapmcp.jar");
        assertThat(p).as("system property imapmcp.jar").isNotBlank();
        Path jar = Path.of(p);
        assertThat(jar).exists().isRegularFile();
        assertThat(Files.size(jar)).isGreaterThan(1_000_000L);
        try (JarFile jf = new JarFile(jar.toFile())) {
            assertThat(jf.getManifest().getMainAttributes().getValue("Main-Class"))
                    .isEqualTo("de.alexanderlindhorst.imapmcp.app.Main");
        }
    }
}
