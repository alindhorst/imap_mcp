package de.alexanderlindhorst.imapmcp.transport.stdio;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;
import org.junit.jupiter.api.Test;

@AnalyzeClasses(packages = "de.alexanderlindhorst.imapmcp.transport.stdio", importOptions = ExcludeFixtures.class)
class StdioArchitectureRulesTest {

    @ArchTest
    static final ArchRule stdioHasNoHttpStack = StdioArchitectureRules.STDIO_HAS_NO_HTTP_STACK;

    @ArchTest
    static final ArchRule stdioStreamsHasNoSlf4j = StdioArchitectureRules.STDIO_STREAMS_HAS_NO_SLF4J;

    /** #143: a renamed package must fail the rules instead of letting them pass on an empty class set. */
    @Test
    void rulesFailOnAnEmptyClassSet() {
        JavaClasses none = new ClassFileImporter()
                .importPackages("de.alexanderlindhorst.imapmcp.transport.stdio.doesnotexist");
        assertThatThrownBy(() -> stdioHasNoHttpStack.check(none)).isInstanceOf(AssertionError.class);
        assertThatThrownBy(() -> stdioStreamsHasNoSlf4j.check(none)).isInstanceOf(AssertionError.class);
    }

    @Test
    void guardDetectsJettyReferenceInFixture() {
        JavaClasses fixture = new ClassFileImporter()
                .importPackages("de.alexanderlindhorst.imapmcp.transport.stdio.archfixture.violating");
        assertThatThrownBy(() -> StdioArchitectureRules.STDIO_HAS_NO_HTTP_STACK.check(fixture))
                .isInstanceOf(AssertionError.class);
    }
}
