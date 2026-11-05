package de.alexanderlindhorst.imapmcp.core;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import static com.tngtech.archunit.base.DescribedPredicate.not;

import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaMethodCall;
import com.tngtech.archunit.lang.ArchRule;
import java.util.Set;

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

    private static final String LEASE = ROOT + ".core.imap.pool.ImapLease";
    private static final String COMMAND_CLASS = ROOT + ".core.tools.write.UidCommands";
    private static final String FOLDER = "jakarta.mail.Folder";
    private static final String MESSAGE = "jakarta.mail.Message";
    private static final String PROTOCOL = "org.eclipse.angus.mail.iap.Protocol";

    /**
     * The v1 read classes that issue read-only raw commands (UID FETCH, STATUS, BODYSTRUCTURE) on an EXAMINEd folder (SPEC
     * 5.4). Named explicitly: a new caller of doCommand/Protocol.command fails rule (e) and has to be added here consciously.
     */
    private static final String[] V1_READ_COMMAND_CLASSES = {
            ROOT + ".core.search.PageFetcher",
            ROOT + ".core.mime.BodyWindowReader",
            ROOT + ".core.tools.GetMessageTool",
            ROOT + ".core.tools.ListAttachmentsTool",
            ROOT + ".core.tools.StatusCountEnricher"};

    /** A call whose target owner is (a subtype of) {@code owner} and whose name is one of {@code names}. */
    private static DescribedPredicate<JavaMethodCall> call(String desc, String owner, String... names) {
        Set<String> n = Set.of(names);
        return new DescribedPredicate<>(desc) {
            @Override
            public boolean test(JavaMethodCall c) {
                return n.contains(c.getName()) && c.getTargetOwner().isAssignableTo(owner);
            }
        };
    }

    /** Same, restricted to a single raw parameter type (to tell {@code close(boolean)}/{@code open(int)} apart). */
    private static DescribedPredicate<JavaMethodCall> callWithParam(String desc, String owner, String name, String paramType) {
        DescribedPredicate<JavaMethodCall> base = call(desc, owner, name);
        return new DescribedPredicate<>(desc) {
            @Override
            public boolean test(JavaMethodCall c) {
                return base.test(c) && c.getTarget().getRawParameterTypes().size() == 1
                        && c.getTarget().getRawParameterTypes().get(0).getName().equals(paramType);
            }
        };
    }

    static final ArchRule FOLDER_OPEN_ONLY_IN_LEASE = noClasses()
            .that().doNotHaveFullyQualifiedName(LEASE)
            .should().callMethodWhere(callWithParam("Folder.open(int)", FOLDER, "open", "int"))
            .as("Folder.open(int) may only be called from ImapLease");

    static final ArchRule READ_WRITE_OPEN_ONLY_FROM_WRITE_PACKAGE = noClasses()
            .that().resideOutsideOfPackage(ROOT + ".core.tools.write..")
            .should().callMethodWhere(call("ImapLease.openReadWrite", LEASE, "openReadWrite"))
            .as("ImapLease.openReadWrite may only be called from ..core.tools.write..");

    static final ArchRule NO_MUTATION_HELPERS = noClasses()
            .should().callMethodWhere(call("Folder mutation helper", FOLDER,
                    "expunge", "setFlags", "copyMessages", "moveMessages", "copyUIDMessages", "moveUIDMessages",
                    "appendMessages", "addMessages", "appendUIDMessages", "create", "delete", "renameTo")
                    .or(call("Message.setFlag/setFlags", MESSAGE, "setFlag", "setFlags")))
            .as("no call to expunge, setFlags/setFlag, the Angus copy/move/append helpers, Folder.create/delete/renameTo");

    static final ArchRule FOLDER_CLOSE_ONLY_IN_LEASE = noClasses()
            .that().doNotHaveFullyQualifiedName(LEASE)
            .should().callMethodWhere(callWithParam("Folder.close(boolean)", FOLDER, "close", "boolean"))
            .as("Folder.close(boolean) may only be called from ImapLease");

    static final ArchRule RAW_COMMANDS_ONLY_IN_COMMAND_CLASS = noClasses()
            .that().doNotHaveFullyQualifiedName(COMMAND_CLASS)
            .and().doNotHaveFullyQualifiedName(LEASE) // ImapLease.doCommand only registers a BYE response handler; sends no command
            .and(not(haveFullyQualifiedNameIn(V1_READ_COMMAND_CLASSES)))
            .should().callMethodWhere(call("IMAPFolder.doCommand", "org.eclipse.angus.mail.imap.IMAPFolder",
                    "doCommand", "doCommandIgnoreFailure", "doOptionalCommand")
                    .or(call("Protocol.command", PROTOCOL, "command", "simpleCommand", "writeCommand")))
            .as("IMAPFolder.doCommand and Protocol.command may only be called from UidCommands (and the named v1 read classes; ImapLease registers a response handler, sends no command)");

    static final ArchRule READ_CLASSES_NEVER_WRITE = noClasses()
            .that(haveFullyQualifiedNameIn(V1_READ_COMMAND_CLASSES))
            .should().dependOnClassesThat().haveFullyQualifiedName(COMMAND_CLASS)
            .as("the v1 read classes must not use the write command class");

    static final ArchRule NO_CONSOLE_OUTPUT = noClasses()
            .should().accessField(System.class, "out")
            .orShould().accessField(System.class, "err")
            .orShould().callMethodWhere(call("Throwable.printStackTrace", "java.lang.Throwable", "printStackTrace"))
            .as("core must not write to System.out/System.err or print stack traces (stdout is the protocol, SPEC 5.6)");

    private static final String DOWNLOAD_SINK = ROOT + ".core.download.DownloadSink";

    /** Static {@code Files} / {@code FileChannel} calls that create, write, link, move or delete (SPEC 5.8). */
    private static final DescribedPredicate<JavaMethodCall> FILE_MUTATION = call("Files mutation", "java.nio.file.Files",
            "newOutputStream", "write", "writeString", "createFile", "createLink", "createSymbolicLink",
            "createTempFile", "createDirectory", "createDirectories", "createTempDirectory", "move", "copy", "delete",
            "deleteIfExists", "newByteChannel", "setPosixFilePermissions", "setAttribute")
            .or(call("FileChannel.open", "java.nio.channels.FileChannel", "open"));

    private static final DescribedPredicate<com.tngtech.archunit.core.domain.JavaConstructorCall> FILE_STREAM_CONSTRUCTOR =
            new DescribedPredicate<>("a file output stream, writer or random access file is constructed") {
                @Override
                public boolean test(com.tngtech.archunit.core.domain.JavaConstructorCall c) {
                    return Set.of("java.io.FileOutputStream", "java.io.FileWriter", "java.io.RandomAccessFile").contains(c.getTargetOwner().getName());
                }
            };

    static final ArchRule FILE_WRITES_ONLY_IN_DOWNLOAD_SINK = noClasses()
            .that().doNotHaveFullyQualifiedName(DOWNLOAD_SINK)
            .should().callMethodWhere(FILE_MUTATION)
            .orShould().callConstructorWhere(FILE_STREAM_CONSTRUCTOR)
            .as("file creation, writes, links, moves and deletes may only happen in the download file sink");

    private static DescribedPredicate<JavaClass> haveFullyQualifiedNameIn(String... names) {
        Set<String> n = Set.of(names);
        return new DescribedPredicate<>("have a fully qualified name in " + n) {
            @Override
            public boolean test(JavaClass c) {
                return n.contains(c.getName());
            }
        };
    }

    private ArchitectureRules() {
    }
}
