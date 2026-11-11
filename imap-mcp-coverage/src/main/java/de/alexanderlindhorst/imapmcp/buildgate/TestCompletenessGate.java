package de.alexanderlindhorst.imapmcp.buildgate;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Stream;
import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamReader;

/**
 * Test-completeness gate (SPEC 5.8, #426), run by {@code exec:java} in {@code verify} of the last reactor module. It is
 * not a surefire test, so {@code -DskipTests} cannot silence it. It reads the surefire and failsafe reports of the
 * reactor written after the session start and fails on: a skipped or aborted test off the allowlist, an {@code *IT}
 * class of {@code imap-mcp-it} without a report with tests, a malformed report, and the flags {@code skipTests},
 * {@code skipITs}, {@code maven.test.skip} unless {@code imapmcp.partialBuild=true}. The summary is one line with test
 * names only; it never echoes skip messages or test output.
 */
public final class TestCompletenessGate {

    private static final String PARTIAL = "-Dimapmcp.partialBuild=true";

    private TestCompletenessGate() {
    }

    /** One allowlist entry: {@code class#method | reason | condition}; method {@code *} covers every test of the class. */
    public record AllowedSkip(String className, String method, String reason, String condition) {
        boolean covers(String cls, String testMethod) {
            if (!className.equals(cls)) {
                return false;
            }
            return method.equals("*") || method.equals(baseName(testMethod));
        }

        private static String baseName(String name) {
            int cut = name.length();
            for (char c : new char[] {'(', '['}) {
                int i = name.indexOf(c);
                if (i >= 0) {
                    cut = Math.min(cut, i);
                }
            }
            return name.substring(0, cut);
        }
    }

    public record Config(Path root, long sessionStartMillis, boolean skipTests, boolean skipITs,
            boolean mavenTestSkip, boolean partialBuild) {
    }

    public record Result(List<String> violations, String summary) {
        public boolean ok() {
            return violations.isEmpty();
        }
    }

    /** What one report says: the suite name, its test count and the skipped or aborted tests. */
    private record Report(String suite, int tests, List<String> skipped) {
    }

    public static List<AllowedSkip> parseAllowlist(String text) {
        List<AllowedSkip> out = new ArrayList<>();
        for (String raw : text.split("\\R")) {
            String line = raw.strip();
            if (line.isEmpty() || line.startsWith("#")) {
                continue;
            }
            String[] parts = line.split("\\|", -1);
            String[] id = parts[0].strip().split("#", -1);
            if (id.length != 2 || id[0].isBlank() || id[1].isBlank()) {
                throw new IllegalArgumentException("allowed-skips.txt: expected 'class#method | reason | condition'");
            }
            out.add(new AllowedSkip(id[0].strip(), id[1].strip(), parts.length > 1 ? parts[1].strip() : "",
                    parts.length > 2 ? parts[2].strip() : ""));
        }
        return List.copyOf(out);
    }

    public static Result check(Config config, List<AllowedSkip> allowlist) {
        List<String> violations = new ArrayList<>();
        if (!config.partialBuild()) {
            flag(violations, config.skipTests(), "skipTests");
            flag(violations, config.skipITs(), "skipITs");
            flag(violations, config.mavenTestSkip(), "maven.test.skip");
        }

        List<Report> reports = new ArrayList<>();
        List<Report> itReports = new ArrayList<>();
        for (Path module : children(config.root())) {
            for (String dir : new String[] {"surefire-reports", "failsafe-reports"}) {
                for (Path file : reportFiles(module.resolve("target").resolve(dir))) {
                    if (lastModified(file) < config.sessionStartMillis()) {
                        continue; // left over from an earlier run
                    }
                    try {
                        Report r = parse(file);
                        reports.add(r);
                        if (dir.equals("failsafe-reports")) {
                            itReports.add(r);
                        }
                    } catch (IOException | XMLStreamException e) {
                        violations.add("malformed report " + module.getFileName() + "/" + file.getFileName());
                    }
                }
            }
        }

        int unexpected = 0;
        int allowed = 0;
        int tests = 0;
        if (!config.partialBuild()) {
            Set<String> offenders = new TreeSet<>();
            for (Report r : reports) {
                tests += r.tests();
                for (String id : r.skipped()) {
                    String cls = id.substring(0, id.indexOf('#'));
                    String method = id.substring(id.indexOf('#') + 1);
                    if (allowlist.stream().anyMatch(a -> a.covers(cls, method))) {
                        allowed++;
                    } else {
                        unexpected++;
                        offenders.add(id);
                    }
                }
            }
            if (!offenders.isEmpty()) {
                violations.add("skipped or aborted tests off the allowlist: " + String.join(", ", offenders));
            }
            List<String> missing = new ArrayList<>();
            for (String itClass : itClasses(config.root())) {
                int n = itReports.stream().filter(r -> r.suite().equals(itClass) || r.suite().startsWith(itClass + "$"))
                        .mapToInt(Report::tests).sum();
                if (n == 0) {
                    missing.add(itClass);
                }
            }
            if (!missing.isEmpty()) {
                violations.add("no integration test report with tests for: " + String.join(", ", missing));
            }
        }

        String summary;
        if (violations.isEmpty()) {
            summary = config.partialBuild()
                    ? "test-completeness gate: OK (partial build, " + PARTIAL + "; report checks off)"
                    : "test-completeness gate: OK (" + reports.size() + " reports, " + tests + " tests, " + allowed
                            + " allowed skips)";
        } else {
            summary = "test-completeness gate: FAILED (" + violations.size() + " problems"
                    + (unexpected > 0 ? ", " + unexpected + " unexpected skips" : "") + "): "
                    + String.join("; ", violations);
        }
        return new Result(List.copyOf(violations), summary);
    }

    private static void flag(List<String> violations, boolean set, String name) {
        if (set) {
            violations.add(name + " is set; the build is partial. Add " + PARTIAL + " to admit it (no evidence for the merge gate)");
        }
    }

    private static List<Path> children(Path root) {
        try (Stream<Path> s = Files.list(root)) {
            return s.filter(Files::isDirectory).sorted().toList();
        } catch (IOException e) {
            throw new IllegalStateException("cannot list " + root.getFileName(), e);
        }
    }

    private static List<Path> reportFiles(Path dir) {
        if (!Files.isDirectory(dir)) {
            return List.of();
        }
        try (Stream<Path> s = Files.list(dir)) {
            return s.filter(p -> {
                String n = p.getFileName().toString();
                return n.startsWith("TEST-") && n.endsWith(".xml");
            }).sorted().toList();
        } catch (IOException e) {
            throw new IllegalStateException("cannot list " + dir.getFileName(), e);
        }
    }

    private static long lastModified(Path file) {
        try {
            return Files.getLastModifiedTime(file).toMillis();
        } catch (IOException e) {
            return 0;
        }
    }

    /** Fully qualified names of the {@code *IT} classes under {@code imap-mcp-it/src/test/java}. */
    private static List<String> itClasses(Path root) {
        Path src = root.resolve("imap-mcp-it").resolve("src").resolve("test").resolve("java");
        if (!Files.isDirectory(src)) {
            return List.of();
        }
        try (Stream<Path> s = Files.walk(src)) {
            return s.filter(p -> p.getFileName().toString().endsWith("IT.java")).map(p -> {
                String rel = src.relativize(p).toString().replace('\\', '/');
                return rel.substring(0, rel.length() - ".java".length()).replace('/', '.');
            }).sorted().toList();
        } catch (IOException e) {
            throw new IllegalStateException("cannot list the integration test sources", e);
        }
    }

    private static Report parse(Path file) throws IOException, XMLStreamException {
        XMLInputFactory f = XMLInputFactory.newFactory();
        f.setProperty(XMLInputFactory.SUPPORT_DTD, false);
        f.setProperty(XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES, false);
        try (InputStream in = Files.newInputStream(file)) {
            XMLStreamReader r = f.createXMLStreamReader(in);
            try {
                String suite = null;
                int tests = 0;
                List<String> skipped = new ArrayList<>();
                String current = null;
                int depth = 0;
                while (r.hasNext()) {
                    int ev = r.next();
                    if (ev == XMLStreamConstants.START_ELEMENT) {
                        depth++;
                        String n = r.getLocalName();
                        if (depth == 1) {
                            if (!n.equals("testsuite")) {
                                throw new XMLStreamException("not a testsuite report");
                            }
                            suite = r.getAttributeValue(null, "name");
                        } else if (n.equals("testcase")) {
                            tests++;
                            current = r.getAttributeValue(null, "classname") + "#" + r.getAttributeValue(null, "name");
                        } else if (n.equals("skipped") && current != null) {
                            skipped.add(current);
                        }
                    } else if (ev == XMLStreamConstants.END_ELEMENT) {
                        depth--;
                        if (r.getLocalName().equals("testcase")) {
                            current = null;
                        }
                    }
                }
                if (suite == null) {
                    throw new XMLStreamException("testsuite without a name");
                }
                return new Report(suite, tests, skipped);
            } finally {
                r.close();
            }
        }
    }

    static long parseStart(String s) {
        if (s.length() == 17) {
            return LocalDateTime.parse(s, DateTimeFormatter.ofPattern("yyyyMMddHHmmssSSS")).toInstant(ZoneOffset.UTC)
                    .toEpochMilli();
        }
        return Long.parseLong(s);
    }

    /**
     * Arguments: root directory, session start ({@code maven.build.timestamp}, format {@code yyyyMMddHHmmssSSS}, UTC;
     * a plain epoch millisecond number is accepted too), then skipTests, skipITs, maven.test.skip and
     * imapmcp.partialBuild as true/false. Throws (so exec:java fails the build) instead of calling System.exit.
     */
    public static void main(String[] args) throws IOException {
        if (args.length != 6) {
            throw new IllegalArgumentException("usage: <root> <sessionStartMillis> <skipTests> <skipITs> <mavenTestSkip> <partialBuild>");
        }
        String allow;
        try (InputStream in = TestCompletenessGate.class.getResourceAsStream("/allowed-skips.txt")) {
            if (in == null) {
                throw new IllegalStateException("allowed-skips.txt is missing from the classpath");
            }
            allow = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
        Config config = new Config(Path.of(args[0]), parseStart(args[1]), Boolean.parseBoolean(args[2]),
                Boolean.parseBoolean(args[3]), Boolean.parseBoolean(args[4]), Boolean.parseBoolean(args[5]));
        Result result = check(config, parseAllowlist(allow));
        if (!result.ok()) {
            throw new IllegalStateException(result.summary());
        }
        System.out.println(result.summary());
    }
}
