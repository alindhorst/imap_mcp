package de.alexanderlindhorst.imapmcp.transport.stdio;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.lang.ArchRule;

/** Placeholder rule (red step). */
final class StdioArchitectureRules {
    static final ArchRule STDIO_HAS_NO_HTTP_STACK = noClasses()
            .should().dependOnClassesThat().resideInAnyPackage("does.not.exist..");

    private StdioArchitectureRules() {
    }
}
