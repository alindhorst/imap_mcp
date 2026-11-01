package de.alexanderlindhorst.imapmcp.app;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

@AnalyzeClasses(packages = "de.alexanderlindhorst.imapmcp.app", importOptions = ImportOption.DoNotIncludeTests.class)
class AppArchitectureTest {

    /** AC4: the transport-agnostic wiring never reaches HTTP or Jetty; only the http launcher may. */
    @ArchTest
    static final ArchRule wiringIsFreeOfHttpAndJetty = noClasses().that().resideInAPackage("..app..")
            .and().haveSimpleNameNotStartingWith("HttpLocal")
            .should().dependOnClassesThat().resideInAnyPackage("..transport.http..", "org.eclipse.jetty..",
                    "jakarta.servlet..")
            .allowEmptyShould(true);
}
