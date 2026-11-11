package de.alexanderlindhorst.imapmcp.app;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.dataformat.yaml.YAMLMapper;

/** #438: static checks of the Gitea Actions workflow, ci/verify.sh and doc/CI.md (nothing is executed). */
class CiWorkflowTest {

    // Surefire runs with the module directory as working directory.
    private static final Path ROOT = Path.of("..");
    private static final Path WORKFLOW = ROOT.resolve(".gitea/workflows/verify.yml");
    private static final Pattern SHA_REF = Pattern.compile("[^@\\s]+@[0-9a-f]{40}(\\s+#.*)?");

    private static String read(Path p) throws IOException {
        return Files.readString(p, StandardCharsets.UTF_8);
    }

    private static JsonNode workflow() throws IOException {
        return YAMLMapper.builder().build().readTree(read(WORKFLOW));
    }

    private static JsonNode verifyJob() throws IOException {
        JsonNode job = workflow().path("jobs").path("verify");
        assertThat(job.isObject()).as("job named verify").isTrue();
        return job;
    }

    /** Executable lines of the script: comments and blank lines are dropped. */
    private static List<String> scriptLines(String text) {
        List<String> out = new ArrayList<>();
        for (String l : text.split("\n")) {
            String t = l.strip();
            if (!t.isEmpty() && !t.startsWith("#")) {
                out.add(t);
            }
        }
        return out;
    }

    @Test
    void everyActionIsPinnedByACommitSha() throws IOException {
        List<String> uses = new ArrayList<>();
        for (JsonNode step : verifyJob().path("steps")) {
            if (step.has("uses")) {
                uses.add(step.path("uses").asString());
            }
        }
        assertThat(uses).isNotEmpty().allMatch(u -> SHA_REF.matcher(u).matches());
        // the raw text too: no "uses:" hidden elsewhere (e.g. a reusable workflow) may use a tag
        for (String l : read(WORKFLOW).split("\n")) {
            if (l.strip().startsWith("uses:") || l.strip().startsWith("- uses:")) {
                String ref = l.substring(l.indexOf("uses:") + 5).strip();
                assertThat(ref).matches(SHA_REF);
            }
        }
    }

    @Test
    void theJobRunsTheScriptAndNothingElseInvokesMaven() throws IOException {
        List<String> runs = new ArrayList<>();
        for (JsonNode step : verifyJob().path("steps")) {
            if (step.has("run")) {
                runs.add(step.path("run").asString().strip());
            }
        }
        assertThat(runs).contains("ci/verify.sh");
        assertThat(String.join("\n", runs)).doesNotContain("mvn");
        assertThat(read(WORKFLOW)).doesNotContain("mvn ");
    }

    @Test
    void theWorkflowRunsTheFullBuildAndTakesNoShortcut() throws IOException {
        String text = read(WORKFLOW);
        assertThat(text).doesNotContainIgnoringCase("skipITs")
                .doesNotContainIgnoringCase("skipTests")
                .doesNotContainIgnoringCase("partialBuild")
                .doesNotContain("maven.test.skip");
        assertThat(read(ROOT.resolve("ci/verify.sh"))).doesNotContainIgnoringCase("skipITs")
                .doesNotContainIgnoringCase("skipTests")
                .doesNotContainIgnoringCase("partialBuild")
                .doesNotContain("maven.test.skip");
    }

    @Test
    void theJobHasATimeoutNoSecretsAndTheRightTriggers() throws IOException {
        JsonNode wf = workflow();
        JsonNode job = verifyJob();
        assertThat(job.path("timeout-minutes").asInt()).isEqualTo(60);
        assertThat(job.path("runs-on").asString()).isNotBlank();
        assertThat(read(WORKFLOW)).doesNotContain("secrets.");
        // "on" is a YAML 1.1 boolean for some parsers; accept either key
        JsonNode on = wf.has("on") ? wf.get("on") : wf.get("true");
        assertThat(on.path("pull_request").path("branches").toString()).contains("master");
        assertThat(on.path("push").path("branches").toString()).contains("master");
        assertThat(wf.path("concurrency").path("group").asString()).contains("github.ref");
    }

    @Test
    void theMavenCacheIsKeyedOnAllPomFiles() throws IOException {
        String text = read(WORKFLOW);
        assertThat(text).contains("hashFiles('**/pom.xml')").contains("~/.m2/repository");
    }

    @Test
    void theScriptIsStrictRefusesRootAndAssertsTheJdk() throws IOException {
        String script = read(ROOT.resolve("ci/verify.sh"));
        List<String> lines = scriptLines(script);
        assertThat(lines).contains("set -euo pipefail");
        assertThat(script).contains("id -u").contains("refus");
        assertThat(script).containsPattern("JDK_VERSION=\"?26\\.\\d+(\\.\\d+)*\"?");
        assertThat(script).contains("java -version");
        assertThat(lines).contains("mvn -B verify");
        assertThat(lines.stream().filter(l -> l.contains("mvn")).toList())
                .as("the only mvn invocation").containsExactly("mvn -B verify");
        assertThat(Files.isExecutable(ROOT.resolve("ci/verify.sh"))).isTrue();
    }

    @Test
    void theJdkIsAnExactTemurinBuildVerifiedBySha256() throws IOException {
        String script = read(ROOT.resolve("ci/install-jdk.sh"));
        assertThat(scriptLines(script)).contains("set -euo pipefail");
        assertThat(script).containsPattern("JDK_SHA256=\"[0-9a-f]{64}\"")
                .containsPattern("JDK_VERSION=\"26\\.\\d+(\\.\\d+)*\"")
                .contains("temurin26-binaries").contains("sha256sum");
        assertThat(script).doesNotContain("latest");
        assertThat(Files.isExecutable(ROOT.resolve("ci/install-jdk.sh"))).isTrue();
    }

    @Test
    void theReportsAreCollectedAsArtifacts() throws IOException {
        assertThat(read(ROOT.resolve("ci/verify.sh"))).contains("surefire-reports").contains("failsafe-reports")
                .contains("it-compose");
        boolean upload = false;
        for (JsonNode step : verifyJob().path("steps")) {
            upload |= step.path("uses").asString().startsWith("actions/upload-artifact@");
        }
        assertThat(upload).isTrue();
    }

    @Test
    void theCiDocumentSaysWhatTheOwnerHasToDoAndHoldsNoSecret() throws IOException {
        String doc = read(ROOT.resolve("doc/CI.md"));
        assertThat(doc).contains("host mode").contains("non-root").contains("docker compose")
                .contains("verify").contains("required").contains("--token");
        assertThat(doc).doesNotContainPattern("(?i)(token|secret)\\s*[:=]\\s*[A-Za-z0-9]{16,}");
    }
}
