package de.alexanderlindhorst.imapmcp.app;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;
import org.junit.jupiter.api.Test;

@AnalyzeClasses(packages = "de.alexanderlindhorst.imapmcp.app", importOptions = ImportOption.DoNotIncludeTests.class)
class AppArchitectureTest {

    /** AC4: the transport-agnostic wiring never reaches HTTP or Jetty; only the http launcher may. */
    @ArchTest
    static final ArchRule wiringIsFreeOfHttpAndJetty = noClasses().that().resideInAPackage("..app..")
            .and().haveSimpleNameNotStartingWith("HttpLocal").and().haveSimpleNameNotStartingWith("HttpPublic")
            .should().dependOnClassesThat().resideInAnyPackage("..transport.http..", "org.eclipse.jetty..",
                    "jakarta.servlet..");

    /** #47 AC4: the dispatcher loads launchers by name only, so the stdio path never pulls in the HTTP launcher. */
    @ArchTest
    static final ArchRule mainDoesNotReferenceLaunchersStatically = noClasses().that().haveSimpleName("Main")
            .should().dependOnClassesThat().haveSimpleNameStartingWith("StdioMain")
            .orShould().dependOnClassesThat().haveSimpleNameStartingWith("HttpLocalMain")
            .orShould().dependOnClassesThat().haveSimpleNameStartingWith("HttpPublicMain");

    /** #47: the launchers hold no logger field (logging must not initialise before the bootstrap). */
    @ArchTest
    static final ArchRule launchersHaveNoLoggerFields = com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noFields()
            .that().areDeclaredInClassesThat().haveSimpleName("StdioMain")
            .or().areDeclaredInClassesThat().haveSimpleName("Main")
            .should().haveRawType("org.slf4j.Logger");

    /** #497: stdio and http-local never construct the XOAUTH2 refresh chain (SPEC 5.5, http-public only). */
    @ArchTest
    static final ArchRule onlyPublicWiringBuildsTheRefreshChain = noClasses().that().resideInAPackage("..app..")
            .and().doNotHaveSimpleName("PublicComponents").and().doNotHaveSimpleName("PublicOAuthUpstream")
            .should().dependOnClassesThat().haveSimpleName("PublicOAuthUpstream")
            .orShould().dependOnClassesThat().resideInAPackage("..store.oauth..")
            .orShould().dependOnClassesThat().haveSimpleName("CachingOAuthAccessTokens");

    /** #497: the http-public wiring does build it (the rule above is not vacuous). */
    @Test
    void publicComponentsBuildsTheRefreshChain() {
        JavaClasses app = new ClassFileImporter().withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPackages("de.alexanderlindhorst.imapmcp.app");
        com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes().that().haveSimpleName("PublicComponents")
                .should().dependOnClassesThat().haveSimpleName("PublicOAuthUpstream").check(app);
    }

    /** #143: a renamed package or class must fail the rules instead of letting them pass on an empty selection. */
    @Test
    void rulesFailOnAnEmptyClassSet() {
        JavaClasses none = new ClassFileImporter().importPackages("de.alexanderlindhorst.imapmcp.app.doesnotexist");
        for (ArchRule rule : new ArchRule[] {wiringIsFreeOfHttpAndJetty, mainDoesNotReferenceLaunchersStatically,
            launchersHaveNoLoggerFields, onlyPublicWiringBuildsTheRefreshChain}) {
            assertThatThrownBy(() -> rule.check(none)).isInstanceOf(AssertionError.class);
        }
    }
}
