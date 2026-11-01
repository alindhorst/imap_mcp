package de.alexanderlindhorst.imapmcp.app;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.jar.JarFile;
import java.util.jar.Manifest;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * Packaging check of the shaded jar (classifier {@code all}). Runs in failsafe ({@code integration-test}), after
 * {@code package}; the jar path arrives as system property {@code shaded.jar}.
 */
class ShadedJarIT {

    private static Path jar;

    @BeforeAll
    static void locateJar() {
        String p = System.getProperty("shaded.jar");
        assertThat(p).as("system property shaded.jar").isNotBlank();
        jar = Path.of(p);
        assertThat(jar).exists();
    }

    private static String text(String entry) throws IOException {
        try (JarFile jf = new JarFile(jar.toFile())) {
            var e = jf.getJarEntry(entry);
            assertThat(e).as("entry " + entry).isNotNull();
            return new String(jf.getInputStream(e).readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    @Test
    void manifestHasMainClassMultiReleaseAndImplementationVersion() throws IOException {
        try (JarFile jf = new JarFile(jar.toFile())) {
            Manifest mf = jf.getManifest();
            var attrs = mf.getMainAttributes();
            assertThat(attrs.getValue("Main-Class")).isEqualTo("de.alexanderlindhorst.imapmcp.app.Main");
            assertThat(attrs.getValue("Multi-Release")).isEqualTo("true");
            assertThat(attrs.getValue("Implementation-Title")).isNotBlank();
            assertThat(attrs.getValue("Implementation-Version")).isNotBlank().isNotEqualTo("dev");
        }
    }

    @Test
    void appVersionReportsTheJarVersion() throws Exception {
        try (URLClassLoader cl = new URLClassLoader(new URL[] {jar.toUri().toURL()}, null)) {
            Class<?> c = Class.forName("de.alexanderlindhorst.imapmcp.app.AppVersion", true, cl);
            var m = c.getDeclaredMethod("version");
            m.setAccessible(true);
            String version = (String) m.invoke(null);
            assertThat(version).isNotEqualTo("dev").isEqualTo(System.getProperty("project.version"));
        }
    }

    @Test
    void javamailProvidersAreMergedFromAngusAndGimap() throws IOException {
        // angus-mail 2.x keeps imap/imaps in javamail.default.providers and puts only pop3 into javamail.providers;
        // gimap adds gimap/gimaps to the same file name. Both contributions must survive the merge.
        for (String name : List.of("META-INF/javamail.providers", "META-INF/jakarta.providers")) {
            assertThat(text(name)).as(name).contains("protocol=pop3;").contains("protocol=gimaps;");
        }
        assertThat(text("META-INF/javamail.default.providers")).contains("protocol=imaps;");
    }

    @Test
    void mailcapFilesAreMerged() throws IOException {
        assertThat(text("META-INF/mailcap")).isNotBlank();
    }

    @Test
    void providerServiceFileListsAngusAndGimap() throws IOException {
        String services = text("META-INF/services/jakarta.mail.Provider");
        assertThat(services).contains("org.eclipse.angus.mail.imap.IMAPProvider").contains("IMAPSSLProvider")
                .contains("org.eclipse.angus.mail.gimap.GmailProvider").contains("GmailSSLProvider");
    }

    @Test
    void mcpSdkServiceFilesArePresent() throws IOException {
        try (JarFile jf = new JarFile(jar.toFile())) {
            assertThat(jf.stream().map(e -> e.getName()).filter(n -> n.startsWith("META-INF/services/io.modelcontextprotocol")))
                    .isNotEmpty();
        }
    }

    @Test
    void noSignatureFilesAndNoModuleInfo() throws IOException {
        Pattern sig = Pattern.compile("META-INF/[^/]+\\.(SF|DSA|RSA|EC)", Pattern.CASE_INSENSITIVE);
        try (JarFile jf = new JarFile(jar.toFile())) {
            List<String> names = jf.stream().map(e -> e.getName()).collect(Collectors.toList());
            assertThat(names).noneMatch(n -> sig.matcher(n).matches());
            assertThat(names).noneMatch(n -> n.equals("module-info.class") || n.endsWith("/module-info.class"));
            assertThat(names).contains("de/alexanderlindhorst/imapmcp/app/Main.class");
        }
    }

    @Test
    void javaDashJarWithoutArgsExitsTwoWithUsageOnStderr() throws Exception {
        ProcessBuilder pb = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-jar", jar.toString());
        pb.environment().keySet().removeIf(k -> k.startsWith("IMAP_MCP_"));
        Path errFile = Files.createTempFile("shaded-err", ".txt");
        Path outFile = Files.createTempFile("shaded-out", ".txt");
        try {
            pb.redirectError(errFile.toFile()).redirectOutput(outFile.toFile());
            Process p = pb.start();
            assertThat(p.waitFor(30, TimeUnit.SECONDS)).isTrue();
            assertThat(p.exitValue()).isEqualTo(2);
            assertThat(Files.readString(errFile)).contains("Usage").contains("--mode");
            assertThat(Files.readString(outFile)).isEmpty();
        } finally {
            Files.deleteIfExists(errFile);
            Files.deleteIfExists(outFile);
        }
    }
}
