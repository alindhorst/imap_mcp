package de.alexanderlindhorst.imapmcp.core;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;
import org.junit.jupiter.api.Test;

@AnalyzeClasses(packages = "de.alexanderlindhorst.imapmcp.core", importOptions = ExcludeFixtures.class)
class ArchitectureRulesTest {

    @ArchTest
    static final ArchRule coreHasNoForbiddenDependencies = ArchitectureRules.CORE_HAS_NO_FORBIDDEN_DEPENDENCIES
            .allowEmptyShould(true);

    @ArchTest
    static final ArchRule sdkOnlyInCoreMcp = ArchitectureRules.SDK_ONLY_IN_CORE_MCP.allowEmptyShould(true);

    @Test
    void guardDetectsJettyReferenceInFixture() {
        JavaClasses fixture = new ClassFileImporter()
                .importPackages("de.alexanderlindhorst.imapmcp.core.archfixture.violating");
        assertThatThrownBy(() -> ArchitectureRules.CORE_HAS_NO_FORBIDDEN_DEPENDENCIES.check(fixture))
                .isInstanceOf(AssertionError.class);
    }
}
