package de.alexanderlindhorst.imapmcp.transport.stdio;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.lang.ArchRule;

/**
 * Architecture rule for {@code imap-mcp-transport-stdio} (SPEC 5.2: Jetty must stay off the stdio
 * classpath): no dependency on Jetty, the servlet API or the HTTP transport. Run against the real
 * code and against a violating fixture by {@link StdioArchitectureRulesTest}.
 */
final class StdioArchitectureRules {
    static final ArchRule STDIO_HAS_NO_HTTP_STACK = noClasses()
            .should().dependOnClassesThat().resideInAnyPackage(
                    "org.eclipse.jetty..",
                    "jakarta.servlet..",
                    "javax.servlet..",
                    "de.alexanderlindhorst.imapmcp.transport.http..")
            .as("stdio transport must not depend on Jetty, the servlet API or the HTTP transport");


    /** StdioStreams runs before logging is initialised (#38): it must not touch SLF4J. */
    static final ArchRule STDIO_STREAMS_HAS_NO_SLF4J = noClasses()
            .that().haveSimpleName("StdioStreams")
            .should().dependOnClassesThat().resideInAnyPackage("org.slf4j..")
            .as("StdioStreams must not depend on SLF4J");

    private StdioArchitectureRules() {
    }
}
