package de.alexanderlindhorst.imapmcp.app;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/** #463: doc/CI.md describes the split (Gitea unit, GitHub verify), holds no secret and no stale runner text. */
class CiDocTest {

    // Surefire runs with the module directory as working directory.
    private static final Path ROOT = Path.of("..");

    private static String doc() throws IOException {
        return Files.readString(ROOT.resolve("doc/CI.md"), StandardCharsets.UTF_8);
    }

    @Test
    void bothContextsAreDescribedInOrderAndUnitIsNoGateEvidence() throws IOException {
        String doc = doc();
        int unit = doc.indexOf("## The Gitea `unit` runner");
        int verify = doc.indexOf("## The GitHub `verify` workflow");
        assertThat(unit).as("unit section").isNotNegative();
        assertThat(verify).as("verify section").isGreaterThan(unit);
        assertThat(doc).contains("no merge-gate evidence").contains(".gitea/workflows/unit.yml")
                .contains(".github/workflows/verify.yml").contains("ci/merge-master.sh");
    }

    @Test
    void githubSectionAndOwnerStepsAreCovered() throws IOException {
        assertThat(doc()).contains("ubuntu-24.04").contains("permissions").contains("push mirror")
                .contains("deploy key").contains("default branch").contains("check-runs");
    }

    @Test
    void mergeMasterSummaryFormatAndBaseAreDocumented() throws IOException {
        assertThat(doc()).contains(
                "head=<sha> master=<sha|absent> merged=<yes|no> basis=<merged|contains|self|head-only> "
                        + "base=<master|main|none>")
                .contains("basis=").contains("base=main");
    }

    @Test
    void containerModeRunnerSetupIsCovered() throws IOException {
        assertThat(doc()).contains("ubuntu-latest").contains("container.network").contains("docker_host")
                .contains("docker.sock").contains("IMAP_MCP_CI_DROP_ROOT").contains("No matching online runner")
                .contains("act_runner register").contains("config.yaml").contains("aarch64").contains("Node.js")
                .contains("container.privileged").contains("valid_volumes");
    }

    @Test
    void noSecretNoOwnerLocalPathAndNoStaleRunnerText() throws IOException {
        String doc = doc();
        assertThat(doc).doesNotContain("--token").doesNotContain(".claude/").doesNotContain("6 GB VM")
                .doesNotContain("Docker for that user");
        assertThat(doc).doesNotContainPattern("(?i)(token|secret)\\s*[:=]\\s*[A-Za-z0-9]{16,}");
        assertThat(doc).doesNotContainPattern("[^\\x20-\\x7E\\n]");
        // the old label may only appear in the sentence explaining why the old workflow never ran
        for (String line : doc.split("\n")) {
            if (line.contains("imap-mcp-host")) {
                assertThat(line).contains("never ran");
            }
        }
    }

    @Test
    void unitRuntimeReportsAndNetworkLessonsAreDocumented() throws IOException {
        assertThat(doc()).contains("timeout-minutes: 40").contains("GHESNotSupportedError").contains("job log")
                .contains("docker network ls").contains("not found").contains("timeout-minutes: 120")
                .doesNotContain("timeout-minutes: 60").doesNotContain("to be measured by the owner");
    }

    @Test
    void readmeBuildSectionPointsToTheCiDocument() throws IOException {
        String readme = Files.readString(ROOT.resolve("README.md"), StandardCharsets.UTF_8);
        int build = readme.indexOf("\n## Build\n");
        int release = readme.indexOf("\n## Release\n");
        assertThat(readme.substring(build, release)).contains("doc/CI.md");
    }
}
