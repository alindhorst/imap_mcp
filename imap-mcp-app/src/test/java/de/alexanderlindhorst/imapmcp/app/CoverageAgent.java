package de.alexanderlindhorst.imapmcp.app;

import java.lang.management.ManagementFactory;
import java.util.List;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Test helper: JVM argument that attaches the JaCoCo agent of the current (test) JVM to a child JVM, writing to its own
 * file {@code <dir of original destfile>/jacoco-children/<uuid>.exec}. Empty when no JaCoCo agent is active
 * ({@code -Djacoco.skip=true}). A copy lives in imap-mcp-app and imap-mcp-it (it cannot see app's test classes).
 */
final class CoverageAgent {

    private static final Pattern DESTFILE = Pattern.compile("(destfile=)(.*?)([^/\\\\,]+)(?=,|$)");

    private CoverageAgent() {
    }

    static List<String> childArgs() {
        return childArgs(ManagementFactory.getRuntimeMXBean().getInputArguments());
    }

    static List<String> childArgs(List<String> inputArguments) {
        for (String arg : inputArguments) {
            if (arg.startsWith("-javaagent:") && arg.contains("org.jacoco.agent")) {
                Matcher m = DESTFILE.matcher(arg);
                if (!m.find()) {
                    return List.of(arg);
                }
                String rewritten = arg.substring(0, m.start()) + m.group(1) + m.group(2) + "jacoco-children/"
                        + UUID.randomUUID() + ".exec" + arg.substring(m.end());
                return List.of(rewritten);
            }
        }
        return List.of();
    }
}
