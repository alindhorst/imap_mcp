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

    /** #47 AC4: the dispatcher loads launchers by name only, so the stdio path never pulls in the HTTP launcher. */
    @ArchTest
    static final ArchRule mainDoesNotReferenceLaunchersStatically = noClasses().that().haveSimpleName("Main")
            .should().dependOnClassesThat().haveSimpleNameStartingWith("StdioMain")
            .orShould().dependOnClassesThat().haveSimpleNameStartingWith("HttpLocalMain")
            .allowEmptyShould(true);

    /** #47: the launchers hold no logger field (logging must not initialise before the bootstrap). */
    @ArchTest
    static final ArchRule launchersHaveNoLoggerFields = com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noFields()
            .that().areDeclaredInClassesThat().haveSimpleName("StdioMain")
            .or().areDeclaredInClassesThat().haveSimpleName("Main")
            .should().haveRawType("org.slf4j.Logger")
            .allowEmptyShould(true);
}
