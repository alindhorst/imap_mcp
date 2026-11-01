package de.alexanderlindhorst.imapmcp.app;

import static org.assertj.core.api.Assertions.assertThat;

import de.alexanderlindhorst.imapmcp.app.config.AppConfig;
import de.alexanderlindhorst.imapmcp.app.config.AppPaths;
import de.alexanderlindhorst.imapmcp.app.config.ConfigLoader;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** #60: the README must stay consistent with the implemented config, env variables and launch commands. */
class ReadmeTest {

    private static String readme;

    @BeforeAll
    static void readReadme() throws IOException {
        // Surefire runs with the module directory as working directory.
        readme = Files.readString(Path.of("..", "README.md"));
    }

    private static List<String> yamlBlocks() {
        List<String> blocks = new ArrayList<>();
        Matcher m = Pattern.compile("(?s)```yaml\\R(.*?)```").matcher(readme);
        while (m.find()) {
            blocks.add(m.group(1));
        }
        return blocks;
    }

    /** AC1: every YAML example loads with the real config loader (secret refs are checked syntactically). */
    @Test
    void yamlExamplesLoadWithConfigLoader(@TempDir Path dir) throws IOException {
        List<String> blocks = yamlBlocks();
        assertThat(blocks).as("README has YAML examples").isNotEmpty();
        assertThat(blocks).anyMatch(b -> b.contains("keyring:imap-mcp/"));
        assertThat(blocks).anyMatch(b -> b.contains("env:"));
        assertThat(blocks).anyMatch(b -> b.contains("file:"));
        int i = 0;
        for (String block : blocks) {
            Path f = dir.resolve("config-" + i++ + ".yaml");
            Files.writeString(f, block);
            AppConfig c = ConfigLoader.load(f, Map.of());
            assertThat(c.accounts()).isNotEmpty();
        }
    }

    /** AC2: env variables named in the README are exactly known ones (config overrides + path variables). */
    @Test
    void namedEnvVariablesAreKnown() {
        Set<String> known = new HashSet<>(AppPaths.ENV_VARS);
        known.addAll(ConfigLoader.ENV_OVERRIDES);
        Set<String> named = new HashSet<>();
        Matcher m = Pattern.compile("\\b(IMAP_MCP_[A-Z_]+|XDG_[A-Z_]+)\\b").matcher(readme);
        while (m.find()) {
            named.add(m.group(1));
        }
        assertThat(named).isNotEmpty().isSubsetOf(known);
        // Everything the code reads is documented.
        assertThat(named).containsAll(known);
    }

    /** AC3: no real addresses, no secret-looking literals. */
    @Test
    void containsNoRealAddressesOrSecrets() {
        Matcher m = Pattern.compile("[A-Za-z0-9._%+-]+@([A-Za-z0-9.-]+\\.[A-Za-z]{2,})").matcher(readme);
        while (m.find()) {
            assertThat(m.group(1)).as("address domain in README").isEqualTo("example.com");
        }
        assertThat(readme).doesNotContainPattern("\\b(?![A-Z_]+\\b)[A-Za-z0-9_-]{32,}");
        Matcher pw = Pattern.compile("(?m)^\\s*(password|token|handleKey):\\s*(\\S+)").matcher(readme);
        while (pw.find()) {
            assertThat(pw.group(2)).as("secret value of " + pw.group(1))
                    .matches("(env:|file:|keyring:).+");
        }
    }

    /** AC4: stdio launch command and trace warning appear verbatim. */
    @Test
    void containsStdioCommandAndTraceWarning() {
        assertThat(readme).contains("java -Xlog:disable -Xlog:all=warning:stderr -jar")
                .contains("--mode stdio");
        assertThat(readme).contains("WARNING")
                .contains("may contain message content (subjects, addresses, bodies)")
                .contains("credentials stay redacted")
                .contains("disable it after diagnosis");
    }

    @Test
    void describesImplementedBehaviourOnly() {
        assertThat(readme).doesNotContain("specification phase").doesNotContain("no code yet")
                .doesNotContain("IMAP_MCP_LOG_MAX_FILE_SIZE");
        assertThat(readme).contains("JDK 25 or newer").contains("java-26").contains("3.9.6");
        assertThat(readme).contains("docker exec").contains("/config/state/http-token")
                .contains("claude mcp add").contains("--transport http").contains("Authorization: Bearer")
                .contains("flavor: auto").contains("gimaps").contains("secret-tool store")
                .contains("security add-generic-password").contains("within 5 minutes");
    }
}
