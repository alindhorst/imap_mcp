package de.alexanderlindhorst.imapmcp.core;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.lang.ArchRule;

/** Placeholder rules (red step). */
final class ArchitectureRules {
    static final ArchRule CORE_HAS_NO_FORBIDDEN_DEPENDENCIES = noClasses()
            .should().dependOnClassesThat().resideInAnyPackage("does.not.exist..");

    static final ArchRule SDK_ONLY_IN_CORE_MCP = noClasses()
            .should().dependOnClassesThat().resideInAnyPackage("does.not.exist..");

    private ArchitectureRules() {
    }
}
