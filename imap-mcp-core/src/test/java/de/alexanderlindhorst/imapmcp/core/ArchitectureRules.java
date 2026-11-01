package de.alexanderlindhorst.imapmcp.core;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.lang.ArchRule;

/**
 * Architecture rules for {@code imap-mcp-core} (SPEC 5.2), defined once.
 *
 * <ul>
 *   <li>{@link #CORE_HAS_NO_FORBIDDEN_DEPENDENCIES}: core must not depend on Jetty, the servlet API
 *       or the transport/store/app modules.
 *   <li>{@link #SDK_ONLY_IN_CORE_MCP}: MCP SDK types may only be used from {@code ..core.mcp..};
 *       tool logic uses our own records, which contains SDK API churn.
 * </ul>
 *
 * <p>Package roots (no split packages across modules): {@code de.alexanderlindhorst.imapmcp.core},
 * {@code .store}, {@code .transport.stdio}, {@code .transport.http}, {@code .app}.
 *
 * <p>The rules are run against the real code by {@link ArchitectureRulesTest}, which also proves
 * they bite by running them against deliberately violating fixtures in
 * {@code core.archfixture..} (excluded from the real scan by {@link ExcludeFixtures}).
 */
final class ArchitectureRules {
    private static final String ROOT = "de.alexanderlindhorst.imapmcp";

    static final ArchRule CORE_HAS_NO_FORBIDDEN_DEPENDENCIES = noClasses()
            .that().resideInAPackage(ROOT + ".core..")
            .should().dependOnClassesThat().resideInAnyPackage(
                    "org.eclipse.jetty..",
                    "jakarta.servlet..",
                    "javax.servlet..",
                    ROOT + ".transport..",
                    ROOT + ".store..",
                    ROOT + ".app..")
            .as("core must not depend on Jetty, the servlet API, transports, store or app");

    static final ArchRule SDK_ONLY_IN_CORE_MCP = noClasses()
            .that().resideOutsideOfPackage(ROOT + ".core.mcp..")
            .should().dependOnClassesThat().resideInAPackage("io.modelcontextprotocol..")
            .as("MCP SDK types may only be used from ..core.mcp..");

    private ArchitectureRules() {
    }
}
