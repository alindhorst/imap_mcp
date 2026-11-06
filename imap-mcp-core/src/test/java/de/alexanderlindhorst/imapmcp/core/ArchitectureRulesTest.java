package de.alexanderlindhorst.imapmcp.core;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;
import org.junit.jupiter.api.Test;

@AnalyzeClasses(packages = "de.alexanderlindhorst.imapmcp.core", importOptions = {ImportOption.DoNotIncludeTests.class, ExcludeFixtures.class})
class ArchitectureRulesTest {

    @ArchTest
    static final ArchRule coreHasNoForbiddenDependencies = ArchitectureRules.CORE_HAS_NO_FORBIDDEN_DEPENDENCIES;

    @ArchTest
    static final ArchRule sdkOnlyInCoreMcp = ArchitectureRules.SDK_ONLY_IN_CORE_MCP;

    @ArchTest
    static final ArchRule folderOpenOnlyInLease = ArchitectureRules.FOLDER_OPEN_ONLY_IN_LEASE;

    @ArchTest
    static final ArchRule readWriteOpenOnlyFromWritePackage = ArchitectureRules.READ_WRITE_OPEN_ONLY_FROM_WRITE_PACKAGE;

    @ArchTest
    static final ArchRule noMutationHelpers = ArchitectureRules.NO_MUTATION_HELPERS;

    @ArchTest
    static final ArchRule folderCloseOnlyInLease = ArchitectureRules.FOLDER_CLOSE_ONLY_IN_LEASE;

    @ArchTest
    static final ArchRule rawCommandsOnlyInCommandClass = ArchitectureRules.RAW_COMMANDS_ONLY_IN_COMMAND_CLASS;

    @ArchTest
    static final ArchRule readClassesNeverWrite = ArchitectureRules.READ_CLASSES_NEVER_WRITE;

    @ArchTest
    static final ArchRule noConsoleOutput = ArchitectureRules.NO_CONSOLE_OUTPUT;

    @ArchTest
    static final ArchRule fileWritesOnlyInDownloadSink = ArchitectureRules.FILE_WRITES_ONLY_IN_DOWNLOAD_SINK;

    @ArchTest
    static final ArchRule extraContentBlocksOnlyFromDownloadAttachment = ArchitectureRules.EXTRA_CONTENT_BLOCKS_ONLY_FROM_DOWNLOAD_ATTACHMENT;

    /** #143: a renamed package must fail the rules instead of letting them pass on an empty class set. */
    @Test
    void rulesFailOnAnEmptyClassSet() {
        JavaClasses none = new ClassFileImporter().importPackages("de.alexanderlindhorst.imapmcp.core.doesnotexist");
        assertThatThrownBy(() -> coreHasNoForbiddenDependencies.check(none)).isInstanceOf(AssertionError.class);
        assertThatThrownBy(() -> sdkOnlyInCoreMcp.check(none)).isInstanceOf(AssertionError.class);
        for (ArchRule rule : new ArchRule[] {folderOpenOnlyInLease, readWriteOpenOnlyFromWritePackage, noMutationHelpers,
                folderCloseOnlyInLease, rawCommandsOnlyInCommandClass, readClassesNeverWrite, noConsoleOutput,
                fileWritesOnlyInDownloadSink}) {
            assertThatThrownBy(() -> rule.check(none)).isInstanceOf(AssertionError.class);
        }
    }

    @Test
    void guardDetectsJettyReferenceInFixture() {
        JavaClasses fixture = new ClassFileImporter()
                .importPackages("de.alexanderlindhorst.imapmcp.core.archfixture.violating");
        assertThatThrownBy(() -> ArchitectureRules.CORE_HAS_NO_FORBIDDEN_DEPENDENCIES.check(fixture))
                .isInstanceOf(AssertionError.class);
    }

    @Test
    void guardDetectsSdkTypeOutsideMcpPackage() {
        JavaClasses fixture = new ClassFileImporter()
                .importPackages("de.alexanderlindhorst.imapmcp.core.archfixture.sdk");
        assertThatThrownBy(() -> ArchitectureRules.SDK_ONLY_IN_CORE_MCP.check(fixture))
                .isInstanceOf(AssertionError.class);
    }

    private static final String FIXTURES = "de.alexanderlindhorst.imapmcp.core.archfixture.writebans.";

    private static void assertDetects(ArchRule rule, String fixture, String... mentions) {
        JavaClasses classes = new ClassFileImporter().importClasses(fixtureClass(fixture));
        var assertion = assertThatThrownBy(() -> rule.check(classes)).isInstanceOf(AssertionError.class);
        for (String m : mentions) {
            assertion.hasMessageContaining(m);
        }
    }

    private static Class<?> fixtureClass(String name) {
        try {
            return Class.forName(FIXTURES + name);
        } catch (ClassNotFoundException e) {
            throw new IllegalStateException(e);
        }
    }

    @Test
    void ruleADetectsFolderOpenOutsideLease() {
        assertDetects(folderOpenOnlyInLease, "FolderOpenFixture", "open(int)");
    }

    @Test
    void ruleBDetectsOpenReadWriteOutsideWritePackage() {
        assertDetects(readWriteOpenOnlyFromWritePackage, "OpenReadWriteFixture", "openReadWrite");
    }

    @Test
    void ruleCDetectsEveryBannedMutationHelper() {
        assertDetects(noMutationHelpers, "MutationFixture", "expunge()", "expunge(", "setFlags(", "setFlag(", "copyMessages(",
                "moveMessages(", "copyUIDMessages(", "moveUIDMessages(", "appendMessages(", "addMessages(",
                "appendUIDMessages(", "create(", "delete(", "renameTo(");
    }

    @Test
    void ruleDDetectsFolderCloseOutsideLease() {
        assertDetects(folderCloseOnlyInLease, "FolderCloseFixture", "close(boolean)");
    }

    @Test
    void ruleEDetectsRawCommandsOutsideCommandClass() {
        assertDetects(rawCommandsOnlyInCommandClass, "RawCommandFixture", "doCommand", "command(");
    }

    /** #207: no System.out/printStackTrace (SPEC 5.6): stdout is the protocol in stdio mode. */
    @Test
    void ruleFDetectsConsoleOutputAndPrintStackTrace() {
        assertDetects(noConsoleOutput, "ConsoleOutputFixture", "System.out", "System.err", "printStackTrace");
    }

    /** #287: file creation, writes, links, moves and deletes only in the download file sink (SPEC 5.8). */
    @Test
    void ruleGDetectsFileWritesOutsideTheDownloadSink() {
        assertDetects(fileWritesOnlyInDownloadSink, "FileWriteFixture", "newOutputStream", "write(", "writeString",
                "createFile", "createLink", "createSymbolicLink", "createTempFile", "createDirectory",
                "createDirectories", "move(", "copy(", "delete(", "deleteIfExists", "newByteChannel", "FileChannel.open",
                "FileOutputStream.<init>");
    }

    /** #305: extra content blocks only from download_attachment. */
    @Test
    void ruleHDetectsExtraContentBlocksOutsideDownloadAttachment() {
        assertDetects(extraContentBlocksOnlyFromDownloadAttachment, "ExtraBlockFixture", "EmbeddedResource");
    }
}
