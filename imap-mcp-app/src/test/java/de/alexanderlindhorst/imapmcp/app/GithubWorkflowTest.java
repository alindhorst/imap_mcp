package de.alexanderlindhorst.imapmcp.app;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.JsonNode;
import tools.jackson.dataformat.yaml.YAMLMapper;

/**
 * #462: checks of the GitHub Actions workflow, ci/merge-master.sh and ci/prepull-images.sh. The workflow is only
 * checked statically; merge-master.sh runs against local temporary git repositories (no network).
 */
class GithubWorkflowTest {

    // Surefire runs with the module directory as working directory.
    private static final Path ROOT = Path.of("..");
    private static final Path WORKFLOW = ROOT.resolve(".github/workflows/verify.yml");
    private static final Path MERGE = ROOT.resolve("ci/merge-master.sh");
    private static final Path PREPULL = ROOT.resolve("ci/prepull-images.sh");
    private static final Pattern SHA_REF = Pattern.compile("[^@\\s]+@[0-9a-f]{40}(\\s+#.*)?");
    private static final Pattern DIGEST_IMAGE =
            Pattern.compile("[A-Za-z0-9._/-]+:[A-Za-z0-9._-]+@sha256:[0-9a-f]{64}");

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

    private static List<String> stepKinds() throws IOException {
        List<String> kinds = new ArrayList<>();
        for (JsonNode step : verifyJob().path("steps")) {
            if (step.has("uses")) {
                kinds.add(step.path("uses").asString().replaceAll("@.*", ""));
            } else {
                kinds.add(step.path("run").asString().strip());
            }
        }
        return kinds;
    }

    @Test
    void nameRunnerTimeoutAndTriggers() throws IOException {
        JsonNode wf = workflow();
        assertThat(wf.path("name").asString()).isEqualTo("verify");
        JsonNode job = verifyJob();
        assertThat(job.path("runs-on").asString()).isEqualTo("ubuntu-24.04");
        assertThat(job.path("timeout-minutes").asInt()).isEqualTo(120);
        JsonNode on = wf.has("on") ? wf.get("on") : wf.get("true");
        Set<String> triggers = new LinkedHashSet<>();
        on.propertyNames().forEach(triggers::add);
        assertThat(triggers).containsExactlyInAnyOrder("push", "workflow_dispatch");
        assertThat(on.path("push").path("branches").toString()).isEqualTo("[\"**\"]");
        assertThat(on.path("push").has("tags")).isFalse();
        String text = read(WORKFLOW);
        assertThat(text).doesNotContain("pull_request_target").doesNotContain("workflow_run");
    }

    @Test
    void leastPrivilegeNoSecretsRyukStaysOn() throws IOException {
        JsonNode permissions = workflow().path("permissions");
        assertThat(permissions.size()).isEqualTo(1);
        assertThat(permissions.path("contents").asString()).isEqualTo("read");
        assertThat(read(WORKFLOW)).doesNotContain("secrets.").doesNotContain("TESTCONTAINERS_");
    }

    @Test
    void everyActionIsPinnedByACommitSha() throws IOException {
        List<String> uses = new ArrayList<>();
        for (JsonNode step : verifyJob().path("steps")) {
            if (step.has("uses")) {
                uses.add(step.path("uses").asString());
            }
        }
        assertThat(uses).hasSize(3).allMatch(u -> SHA_REF.matcher(u).matches());
        for (String l : read(WORKFLOW).split("\n")) {
            if (l.strip().startsWith("uses:") || l.strip().startsWith("- uses:")) {
                assertThat(l.substring(l.indexOf("uses:") + 5).strip()).matches(SHA_REF);
            }
        }
        String text = read(WORKFLOW);
        assertThat(text).contains("actions/checkout@34e114876b0b11c390a56381ad16ebd13914f8d5")
                .contains("actions/cache@0057852bfaa89a56745cba8c7296529d2fc39830")
                .contains("actions/upload-artifact@ea165f8d65b6e75b540449e92b4886f43607fa02");
    }

    @Test
    void stepOrderAndTheScriptIsTheOnlyBuildStep() throws IOException {
        assertThat(stepKinds()).hasSize(7);
        List<String> kinds = stepKinds();
        assertThat(kinds.get(0)).isEqualTo("actions/checkout");
        assertThat(kinds.get(1)).contains("rm -rf").contains("/usr/share/dotnet").contains("/opt/ghc")
                .contains("/usr/local/lib/android").contains("df -h /");
        assertThat(kinds.get(2)).isEqualTo("actions/cache");
        assertThat(kinds.get(3)).isEqualTo("ci/merge-master.sh");
        assertThat(kinds.get(4)).isEqualTo("ci/prepull-images.sh");
        assertThat(kinds.get(5)).isEqualTo("ci/verify.sh");
        assertThat(kinds.get(6)).isEqualTo("actions/upload-artifact");
        String text = read(WORKFLOW);
        assertThat(text).doesNotContain("mvn ").doesNotContainIgnoringCase("skipITs")
                .doesNotContainIgnoringCase("skipTests").doesNotContainIgnoringCase("partialBuild")
                .doesNotContain("maven.test.skip");
        for (JsonNode step : verifyJob().path("steps")) {
            assertThat(step.path("run").asString()).doesNotContain("mvn");
        }
    }

    @Test
    void checkoutConcurrencyCacheAndUpload() throws IOException {
        JsonNode steps = verifyJob().path("steps");
        assertThat(steps.get(0).path("with").path("fetch-depth").asString()).isEqualTo("0");
        JsonNode concurrency = workflow().path("concurrency");
        assertThat(concurrency.path("group").asString()).contains("github.ref");
        String cancel = concurrency.path("cancel-in-progress").asString();
        assertThat(cancel).contains("github.ref != 'refs/heads/master'").contains("github.ref != 'refs/heads/main'");
        assertThat(read(WORKFLOW)).contains("hashFiles('**/pom.xml')").contains("~/.m2/repository");
        JsonNode upload = steps.get(6);
        assertThat(upload.path("if").asString()).isEqualTo("always()");
        assertThat(upload.path("with").path("retention-days").asString()).isEqualTo("7");
        assertThat(upload.path("with").path("path").asString()).isEqualTo("ci-artifacts");
    }

    @Test
    void mergeMasterScriptIsStrictExecutableAndNeverPushes() throws IOException {
        String script = read(MERGE);
        assertThat(scriptLines(script)).contains("set -euo pipefail");
        assertThat(Files.isExecutable(MERGE)).isTrue();
        assertThat(script).contains("--no-ff").contains("GITHUB_STEP_SUMMARY").doesNotContain("git push");
    }

    private record Result(int exit, String stderr) {}

    private static Result run(Path dir, Map<String, String> env, String... cmd) throws Exception {
        ProcessBuilder pb = new ProcessBuilder(cmd).directory(dir.toFile());
        pb.environment().putAll(env);
        Path err = Files.createTempFile("stderr", ".txt");
        pb.redirectError(err.toFile()).redirectOutput(ProcessBuilder.Redirect.DISCARD);
        Process p = pb.start();
        assertThat(p.waitFor(60, TimeUnit.SECONDS)).isTrue();
        String stderr = read(err);
        Files.delete(err);
        return new Result(p.exitValue(), stderr);
    }

    private static String git(Path dir, Map<String, String> env, String... args) throws Exception {
        List<String> cmd = new ArrayList<>(List.of("git"));
        cmd.addAll(List.of(args));
        ProcessBuilder pb = new ProcessBuilder(cmd).directory(dir.toFile()).redirectErrorStream(true);
        pb.environment().putAll(env);
        Process p = pb.start();
        String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8).strip();
        assertThat(p.waitFor(60, TimeUnit.SECONDS)).isTrue();
        assertThat(p.exitValue()).as("git %s: %s", String.join(" ", args), out).isZero();
        return out;
    }

    @Test
    void mergeMasterMergesANewerMasterAndNamesBothShas(@TempDir Path tmp) throws Exception {
        Map<String, String> env = Map.of(
                "GIT_AUTHOR_NAME", "t", "GIT_AUTHOR_EMAIL", "t@example.invalid",
                "GIT_COMMITTER_NAME", "t", "GIT_COMMITTER_EMAIL", "t@example.invalid",
                "GIT_CONFIG_GLOBAL", "/dev/null", "GIT_CONFIG_SYSTEM", "/dev/null");
        Path origin = tmp.resolve("origin.git");
        Path clone = tmp.resolve("clone");
        Files.createDirectories(origin);
        git(origin, env, "init", "--bare", "-b", "master");
        git(tmp, env, "clone", origin.toString(), "clone");
        Files.writeString(clone.resolve("base.txt"), "base\n");
        git(clone, env, "add", ".");
        git(clone, env, "commit", "-m", "base");
        git(clone, env, "push", "origin", "HEAD:refs/heads/master");
        git(clone, env, "checkout", "-b", "feature");
        Files.writeString(clone.resolve("feature.txt"), "feature\n");
        git(clone, env, "add", ".");
        git(clone, env, "commit", "-m", "feature");
        String head = git(clone, env, "rev-parse", "HEAD");
        // master moves on after the branch was cut
        git(clone, env, "checkout", "master");
        Files.writeString(clone.resolve("master.txt"), "master\n");
        git(clone, env, "add", ".");
        git(clone, env, "commit", "-m", "newer master");
        git(clone, env, "push", "origin", "HEAD:refs/heads/master");
        String master = git(clone, env, "rev-parse", "HEAD");
        git(clone, env, "checkout", "feature");
        git(clone, env, "update-ref", "-d", "refs/remotes/origin/master");

        Files.createDirectories(clone.resolve("ci"));
        Path script = clone.resolve("ci/merge-master.sh");
        Files.copy(MERGE, script);
        Files.setPosixFilePermissions(script, Set.of(PosixFilePermission.OWNER_READ,
                PosixFilePermission.OWNER_WRITE, PosixFilePermission.OWNER_EXECUTE));
        Path summary = tmp.resolve("summary.md");
        Map<String, String> runEnv = new java.util.HashMap<>(env);
        runEnv.put("GITHUB_STEP_SUMMARY", summary.toString());
        runEnv.put("GITHUB_REF_NAME", "feature");

        Result r = run(clone, runEnv, script.toString());
        assertThat(r.exit()).as(r.stderr()).isZero();
        String expected = "head=" + head + " master=" + master + " merged=yes";
        assertThat(r.stderr()).contains(expected);
        assertThat(read(summary)).contains(expected);
        assertThat(git(clone, env, "rev-list", "--parents", "-n", "1", "HEAD").split(" ")).hasSize(3);
        assertThat(clone.resolve("master.txt")).exists();
        assertThat(clone.resolve("feature.txt")).exists();
        // nothing was pushed
        assertThat(git(origin, env, "rev-parse", "refs/heads/master")).isEqualTo(master);
        assertThat(git(origin, env, "branch", "--list", "feature")).isEmpty();
    }

    @Test
    void prepullScriptIsStrictExecutableAndHasNoLogin() throws IOException {
        String script = read(PREPULL);
        assertThat(scriptLines(script)).contains("set -euo pipefail");
        assertThat(Files.isExecutable(PREPULL)).isTrue();
        assertThat(script).doesNotContain("docker login").contains("docker pull").contains("compose.yaml")
                .contains("ItImages.java");
    }

    @Test
    void prepullListCoversEveryDigestPinnedImage() throws Exception {
        Set<String> expected = new LinkedHashSet<>();
        for (Path p : List.of(ROOT.resolve("dist/docker/compose.yaml"), ROOT.resolve(
                "imap-mcp-it/src/test/java/de/alexanderlindhorst/imapmcp/it/ItImages.java"))) {
            Matcher m = DIGEST_IMAGE.matcher(read(p));
            while (m.find()) {
                expected.add(m.group());
            }
        }
        assertThat(expected).hasSizeGreaterThanOrEqualTo(7);
        String all = String.join("\n", expected);
        assertThat(all).contains("postgres:").contains("keycloak:").contains("caddy:").contains("dovecot:")
                .contains("squid:");

        ProcessBuilder pb = new ProcessBuilder(PREPULL.toAbsolutePath().toString(), "--list");
        pb.directory(ROOT.toFile());
        Process p = pb.start();
        String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertThat(p.waitFor(30, TimeUnit.SECONDS)).isTrue();
        assertThat(p.exitValue()).isZero();
        assertThat(List.of(out.strip().split("\n"))).containsExactlyInAnyOrderElementsOf(expected);
    }
}
