package de.alexanderlindhorst.imapmcp.buildgate;

import static org.assertj.core.api.Assertions.assertThat;

import de.alexanderlindhorst.imapmcp.buildgate.TestCompletenessGate.AllowedSkip;
import de.alexanderlindhorst.imapmcp.buildgate.TestCompletenessGate.Config;
import de.alexanderlindhorst.imapmcp.buildgate.TestCompletenessGate.Result;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The gate on synthetic report XML in a temp reactor (#426). */
class TestCompletenessGateTest {

    private static final long START = 1_800_000_000_000L;

    @TempDir
    Path root;

    private Path report(String module, String dir, String className, String testcases, long mtime) throws IOException {
        Path d = Files.createDirectories(root.resolve(module).resolve("target").resolve(dir));
        Path f = d.resolve("TEST-" + className + ".xml");
        Files.writeString(f, "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<testsuite name=\"" + className + "\">\n"
                + testcases + "</testsuite>\n", StandardCharsets.UTF_8);
        Files.setLastModifiedTime(f, FileTime.fromMillis(mtime));
        return f;
    }

    private static String passed(String cls, String method) {
        return "<testcase name=\"" + method + "\" classname=\"" + cls + "\" time=\"0.01\"/>\n";
    }

    private static String skipped(String cls, String method) {
        return "<testcase name=\"" + method + "\" classname=\"" + cls + "\" time=\"0\"><skipped message=\"why\"/></testcase>\n";
    }

    private void itSource(String simpleName) throws IOException {
        Path d = Files.createDirectories(root.resolve("imap-mcp-it/src/test/java/p"));
        Files.writeString(d.resolve(simpleName + ".java"), "package p; class " + simpleName + " {}");
    }

    private Result run(boolean skipTests, boolean skipITs, boolean mavenTestSkip, boolean partial,
            List<AllowedSkip> allow) {
        return TestCompletenessGate.check(new Config(root, START, skipTests, skipITs, mavenTestSkip, partial), allow);
    }

    private Result run() {
        return run(false, false, false, false, List.of());
    }

    @Test
    void cleanReactorPasses() throws Exception {
        report("m", "surefire-reports", "a.FooTest", passed("a.FooTest", "ok"), START + 5);
        itSource("FooIT");
        report("imap-mcp-it", "failsafe-reports", "p.FooIT", passed("p.FooIT", "ok"), START + 5);
        Result r = run();
        assertThat(r.ok()).isTrue();
        assertThat(r.summary()).doesNotContain("\n").contains("OK");
    }

    @Test
    void skippedTestOffTheAllowlistFailsAndNamesClassAndMethod() throws Exception {
        report("m", "surefire-reports", "a.FooTest", skipped("a.FooTest", "bar"), START + 5);
        Result r = run();
        assertThat(r.ok()).isFalse();
        assertThat(r.summary()).doesNotContain("\n").contains("a.FooTest#bar");
    }

    @Test
    void sameSkipOnTheAllowlistPasses() throws Exception {
        report("m", "surefire-reports", "a.FooTest", skipped("a.FooTest", "bar"), START + 5);
        Result r = run(false, false, false, false,
                TestCompletenessGate.parseAllowlist("a.FooTest#bar | uid 0 only | running as root"));
        assertThat(r.ok()).isTrue();
    }

    @Test
    void wildcardEntryCoversEveryTestOfTheClassAndParameterisedNames() throws Exception {
        report("m", "surefire-reports", "a.FooTest",
                skipped("a.FooTest", "one") + skipped("a.FooTest", "two(String)[1]"), START + 5);
        assertThat(run(false, false, false, false,
                TestCompletenessGate.parseAllowlist("# comment\n\na.FooTest#* | Windows only | os=windows")).ok()).isTrue();
        assertThat(run(false, false, false, false,
                TestCompletenessGate.parseAllowlist("a.FooTest#one | r | c\na.FooTest#two | r | c")).ok()).isTrue();
    }

    @Test
    void allowlistEntryForAnotherMethodDoesNotCover() throws Exception {
        report("m", "surefire-reports", "a.FooTest", skipped("a.FooTest", "bar"), START + 5);
        assertThat(run(false, false, false, false,
                TestCompletenessGate.parseAllowlist("a.FooTest#baz | r | c")).ok()).isFalse();
    }

    @Test
    void reportsOlderThanTheSessionAreIgnored() throws Exception {
        report("m", "surefire-reports", "a.FooTest", skipped("a.FooTest", "bar"), START - 1000);
        Result r = run();
        assertThat(r.ok()).isTrue();
    }

    @Test
    void staleItReportDoesNotCountAsAReport() throws Exception {
        itSource("FooIT");
        report("imap-mcp-it", "failsafe-reports", "p.FooIT", passed("p.FooIT", "ok"), START - 1000);
        Result r = run();
        assertThat(r.ok()).isFalse();
        assertThat(r.summary()).contains("p.FooIT");
    }

    @Test
    void itClassWithoutReportFails() throws Exception {
        itSource("FooIT");
        Result r = run();
        assertThat(r.ok()).isFalse();
        assertThat(r.summary()).contains("p.FooIT");
    }

    @Test
    void itReportWithZeroTestsFails() throws Exception {
        itSource("FooIT");
        report("imap-mcp-it", "failsafe-reports", "p.FooIT", "", START + 5);
        assertThat(run().ok()).isFalse();
    }

    @Test
    void itReportOfANestedClassCountsForTheOuterClass() throws Exception {
        itSource("FooIT");
        report("imap-mcp-it", "failsafe-reports", "p.FooIT$Inner", passed("p.FooIT$Inner", "ok"), START + 5);
        assertThat(run().ok()).isTrue();
    }

    @Test
    void liveSmokeAndNonItClassesNeedNoReport() throws Exception {
        itSource("GmailReadLiveSmoke");
        itSource("DockerGuardTest");
        assertThat(run().ok()).isTrue();
    }

    @Test
    void deployedItClassesNeedAReportLikeAnyOther() throws Exception {
        itSource("StackDeployedIT");
        assertThat(run().ok()).isFalse();
        report("imap-mcp-it", "failsafe-reports", "p.StackDeployedIT", passed("p.StackDeployedIT", "ok"), START + 5);
        assertThat(run().ok()).isTrue();
    }

    @Test
    void skipItsWithoutPartialBuildFailsNamingTheProperty() {
        Result r = run(false, true, false, false, List.of());
        assertThat(r.ok()).isFalse();
        assertThat(r.summary()).contains("-Dimapmcp.partialBuild=true").contains("skipITs");
    }

    @Test
    void skipTestsAndMavenTestSkipFailWithoutPartialBuild() {
        assertThat(run(true, false, false, false, List.of()).summary()).contains("skipTests");
        assertThat(run(false, false, true, false, List.of()).summary()).contains("maven.test.skip");
    }

    @Test
    void partialBuildAdmitsTheFlagsAndTurnsBothReportChecksOff() throws Exception {
        itSource("FooIT");
        report("m", "surefire-reports", "a.FooTest", skipped("a.FooTest", "bar"), START + 5);
        Result r = run(true, true, true, true, List.of());
        assertThat(r.ok()).isTrue();
        assertThat(r.summary()).contains("partial");
    }

    @Test
    void malformedReportFails() throws Exception {
        Path d = Files.createDirectories(root.resolve("m/target/surefire-reports"));
        Path f = d.resolve("TEST-a.FooTest.xml");
        Files.writeString(f, "<testsuite name=\"a.FooTest\"><testcase name=\"x\"");
        Files.setLastModifiedTime(f, FileTime.fromMillis(START + 5));
        Result r = run();
        assertThat(r.ok()).isFalse();
        assertThat(r.summary()).contains("TEST-a.FooTest.xml");
    }

    @Test
    void summaryNeverEchoesSkipMessagesOrTestOutput() throws Exception {
        Path d = Files.createDirectories(root.resolve("m/target/surefire-reports"));
        Path f = d.resolve("TEST-a.FooTest.xml");
        Files.writeString(f, "<testsuite name=\"a.FooTest\"><testcase name=\"bar\" classname=\"a.FooTest\">"
                + "<skipped message=\"SECRET-SUBJECT\"/></testcase><system-out>SECRET-BODY</system-out></testsuite>");
        Files.setLastModifiedTime(f, FileTime.fromMillis(START + 5));
        assertThat(run().summary()).doesNotContain("SECRET");
    }

    @Test
    void seededAllowlistParsesWithReasonAndCondition() throws Exception {
        try (var in = TestCompletenessGate.class.getResourceAsStream("/allowed-skips.txt")) {
            List<AllowedSkip> list = TestCompletenessGate.parseAllowlist(
                    new String(in.readAllBytes(), StandardCharsets.UTF_8));
            assertThat(list).extracting(AllowedSkip::className).anyMatch(c -> c.endsWith(".StateDirectoryTest"))
                    .anyMatch(c -> c.endsWith(".ProcessCommandRunnerTest"));
            assertThat(list).allSatisfy(e -> assertThat(e.reason()).isNotBlank());
        }
    }
}
