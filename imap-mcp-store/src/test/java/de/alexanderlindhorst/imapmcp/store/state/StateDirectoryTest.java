package de.alexanderlindhorst.imapmcp.store.state;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assumptions.assumeThat;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.LoggerFactory;

class StateDirectoryTest {

    @TempDir
    Path tmp;

    private ListAppender<ILoggingEvent> logs;
    private Logger logger;

    @BeforeEach
    void captureLogs() {
        logger = (Logger) LoggerFactory.getLogger("de.alexanderlindhorst.imapmcp.store.state");
        logger.setLevel(Level.DEBUG);
        // keep logback's default console appender out of the stdout assertion (production logs to file)
        logger.setAdditive(false);
        logs = new ListAppender<>();
        logs.start();
        logger.addAppender(logs);
    }

    @AfterEach
    void detach() {
        logger.detachAppender(logs);
        logger.setAdditive(true);
    }

    private String logText() {
        return logs.list.stream().map(ILoggingEvent::getFormattedMessage).reduce("", (a, b) -> a + "\n" + b);
    }

    private static String b64(byte[] b) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(b);
    }

    // AC1

    @Test
    void firstStartCreatesThreeFiles0600InDirectory0700AndSecondStartReadsSameValues() throws Exception {
        Path dir = tmp.resolve("state");
        StateDirectory first = new StateDirectory(dir);
        char[] token = first.bearerToken();
        byte[] key = first.handleKey();
        byte[] salt = first.installationSalt();

        assertThat(token).hasSize(43);
        assertThat(key).hasSize(32);
        assertThat(salt).hasSize(16);
        assertThat(Files.getPosixFilePermissions(dir)).isEqualTo(PosixFilePermissions.fromString("rwx------"));
        for (String f : new String[] {"http-token", "handle-key", "installation-salt"}) {
            assertThat(Files.getPosixFilePermissions(dir.resolve(f)))
                    .as(f).isEqualTo(PosixFilePermissions.fromString("rw-------"));
        }
        try (Stream<Path> s = Files.list(dir)) {
            assertThat(s.count()).as("no temp files left behind").isEqualTo(3);
        }
        assertThat(Files.readString(dir.resolve("handle-key")).strip()).isEqualTo(b64(key));

        StateDirectory second = new StateDirectory(dir);
        assertThat(second.bearerToken()).isEqualTo(token);
        assertThat(second.handleKey()).isEqualTo(key);
        assertThat(second.installationSalt()).isEqualTo(salt);
    }

    // AC2

    @Test
    void configuredValuesWinAndNothingIsWritten() throws Exception {
        Path dir = tmp.resolve("state");
        char[] token = "t".repeat(40).toCharArray();
        byte[] key = new byte[32];
        Arrays.fill(key, (byte) 7);
        StateDirectory sd = new StateDirectory(dir, token, b64(key).toCharArray());

        assertThat(sd.bearerToken()).isEqualTo(token);
        assertThat(sd.handleKey()).isEqualTo(key);
        assertThat(Files.exists(dir.resolve("http-token"))).isFalse();
        assertThat(Files.exists(dir.resolve("handle-key"))).isFalse();
    }

    // AC3

    @Test
    void shortConfiguredTokenFailsWithoutEchoingIt() {
        String secret = "s3cr3t-token-31-chars-aaaaaaaaaa";
        char[] token = secret.substring(0, 31).toCharArray();
        assertThatThrownBy(() -> new StateDirectory(tmp.resolve("state"), token, null).bearerToken())
                .isInstanceOf(StateDirectoryException.class)
                .hasMessageNotContaining(secret.substring(0, 31));
    }

    @Test
    void configuredHandleKeyOfWrongLengthFailsWithoutEchoingIt() {
        String bad = b64(new byte[16]);
        assertThatThrownBy(() -> new StateDirectory(tmp.resolve("state"), null, bad.toCharArray()).handleKey())
                .isInstanceOf(StateDirectoryException.class)
                .hasMessageNotContaining(bad);
    }

    @Test
    void corruptPersistedHandleKeyFailsWithoutEchoingIt() throws Exception {
        Path dir = tmp.resolve("state");
        new StateDirectory(dir).handleKey();
        String bad = b64(new byte[5]);
        Files.writeString(dir.resolve("handle-key"), bad);
        assertThatThrownBy(() -> new StateDirectory(dir).handleKey())
                .isInstanceOf(StateDirectoryException.class)
                .hasMessageNotContaining(bad);
    }

    // AC4

    @Test
    void concurrentFirstStartsEndUpWithOneValue() throws Exception {
        Path dir = tmp.resolve("state");
        int n = 8;
        ExecutorService pool = Executors.newFixedThreadPool(n);
        CountDownLatch go = new CountDownLatch(1);
        List<Future<List<String>>> results = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            results.add(pool.submit(() -> {
                StateDirectory sd = new StateDirectory(dir);
                go.await();
                return List.of(new String(sd.bearerToken()), b64(sd.handleKey()), b64(sd.installationSalt()));
            }));
        }
        go.countDown();
        List<String> first = results.get(0).get();
        for (Future<List<String>> r : results) {
            assertThat(r.get()).isEqualTo(first);
        }
        pool.shutdown();
        try (Stream<Path> s = Files.list(dir)) {
            assertThat(s.count()).as("no temp files left behind").isEqualTo(3);
        }
    }

    // AC5

    @Test
    void logsContainPathButNeverValuesAndStdStreamsStayEmpty() {
        PrintStream origOut = System.out;
        PrintStream origErr = System.err;
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        System.setOut(new PrintStream(out));
        System.setErr(new PrintStream(err));
        Path dir = tmp.resolve("state");
        String token;
        String key;
        String salt;
        try {
            StateDirectory sd = new StateDirectory(dir);
            token = new String(sd.bearerToken());
            key = b64(sd.handleKey());
            salt = b64(sd.installationSalt());
        } finally {
            System.setOut(origOut);
            System.setErr(origErr);
        }
        String log = logText();
        assertThat(log).contains(dir.resolve("http-token").toString());
        assertThat(log).doesNotContain(token, key, salt);
        assertThat(out.size()).isZero();
        assertThat(err.size()).isZero();
    }

    @Test
    void widerExistingDirectoryWarnsOnceWithPathOnly() throws Exception {
        Path dir = tmp.resolve("state");
        Files.createDirectory(dir, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwxr-xr-x")));
        Files.setPosixFilePermissions(dir, PosixFilePermissions.fromString("rwxr-xr-x"));
        StateDirectory sd = new StateDirectory(dir);
        sd.bearerToken();
        sd.handleKey();
        sd.installationSalt();
        List<ILoggingEvent> warns = logs.list.stream().filter(e -> e.getLevel() == Level.WARN).toList();
        assertThat(warns).hasSize(1);
        assertThat(warns.get(0).getFormattedMessage()).contains(dir.toString());
    }

    // #147 item 3: tighten what we own

    @Test
    void widerExistingDirectoryIsTightenedTo0700() throws Exception {
        Path dir = tmp.resolve("state");
        Files.createDirectory(dir);
        Files.setPosixFilePermissions(dir, PosixFilePermissions.fromString("rwxr-xr-x"));
        new StateDirectory(dir).handleKey();
        assertThat(Files.getPosixFilePermissions(dir)).isEqualTo(PosixFilePermissions.fromString("rwx------"));
    }

    @Test
    void widerExistingStateFileIsTightenedTo0600AndKeepsItsValue() throws Exception {
        Path dir = tmp.resolve("state");
        byte[] key = new StateDirectory(dir).handleKey();
        Files.setPosixFilePermissions(dir.resolve("handle-key"), PosixFilePermissions.fromString("rw-r--r--"));
        assertThat(new StateDirectory(dir).handleKey()).isEqualTo(key);
        assertThat(Files.getPosixFilePermissions(dir.resolve("handle-key")))
                .isEqualTo(PosixFilePermissions.fromString("rw-------"));
    }

    @Test
    void stateFileThatIsASymlinkIsRejected() throws Exception {
        Path dir = tmp.resolve("state");
        new StateDirectory(dir).handleKey();
        Path real = tmp.resolve("elsewhere");
        Files.writeString(real, b64(new byte[32]));
        Files.delete(dir.resolve("handle-key"));
        Files.createSymbolicLink(dir.resolve("handle-key"), real);
        assertThatThrownBy(() -> new StateDirectory(dir).handleKey()).isInstanceOf(StateDirectoryException.class);
    }

    @Test
    void stateFileThatIsADirectoryIsRejected() throws Exception {
        Path dir = tmp.resolve("state");
        new StateDirectory(dir).handleKey();
        Files.delete(dir.resolve("handle-key"));
        Files.createDirectory(dir.resolve("handle-key"));
        assertThatThrownBy(() -> new StateDirectory(dir).handleKey()).isInstanceOf(StateDirectoryException.class);
    }

    private static final StateDirectory.PermissionSetter REFUSED = (p, perms) -> {
        throw new java.nio.file.AccessDeniedException(p.toString());
    };

    private void assertTokenFailsAndKeyMaterialIsEphemeral(StateDirectory sd, Path dir) throws Exception {
        assertThatThrownBy(sd::bearerToken).isInstanceOf(StateDirectoryException.class);
        byte[] key = sd.handleKey();
        assertThat(key).hasSize(32);
        assertThat(sd.installationSalt()).hasSize(16);
        assertThat(logs.list).anyMatch(e -> e.getLevel() == Level.WARN
                && e.getFormattedMessage().contains("handles will be invalid after restart"));
        assertThat(logs.list).anyMatch(e -> e.getLevel() == Level.WARN
                && e.getFormattedMessage().contains("account handles will change after restart"));
        try (Stream<Path> s = Files.list(dir)) {
            assertThat(s.map(f -> f.getFileName().toString())).as("nothing written")
                    .doesNotContain("http-token", "installation-salt").noneMatch(n -> n.endsWith(".tmp"));
        }
    }

    @Test
    void refusedChmodOnALooseDirectoryFailsTheTokenAndMakesKeyAndSaltEphemeral() throws Exception {
        Path dir = tmp.resolve("state");
        Files.createDirectory(dir);
        Files.setPosixFilePermissions(dir, PosixFilePermissions.fromString("rwxr-xr-x"));
        assertTokenFailsAndKeyMaterialIsEphemeral(new StateDirectory(dir, null, null, REFUSED), dir);
        assertThat(Files.getPosixFilePermissions(dir)).isEqualTo(PosixFilePermissions.fromString("rwxr-xr-x"));
    }

    @Test
    void refusedChmodOnALooseExistingFileFailsTheTokenAndMakesKeyAndSaltEphemeral() throws Exception {
        Path dir = tmp.resolve("state");
        new StateDirectory(dir).bearerToken();
        Files.setPosixFilePermissions(dir.resolve("http-token"), PosixFilePermissions.fromString("rw-r--r--"));
        StateDirectory sd = new StateDirectory(dir, null, null, REFUSED);
        assertThatThrownBy(sd::bearerToken).isInstanceOf(StateDirectoryException.class);
        // a loose handle-key file: the key becomes ephemeral and the file is left as it was
        new StateDirectory(dir).handleKey();
        Files.setPosixFilePermissions(dir.resolve("handle-key"), PosixFilePermissions.fromString("rw-r--r--"));
        String before = Files.readString(dir.resolve("handle-key"));
        assertThat(sd.handleKey()).hasSize(32).isNotEqualTo(Base64.getUrlDecoder().decode(before.strip()));
        assertThat(logs.list).anyMatch(e -> e.getLevel() == Level.WARN
                && e.getFormattedMessage().contains("handles will be invalid after restart"));
        assertThat(Files.readString(dir.resolve("handle-key"))).isEqualTo(before);
    }

    @Test
    void chmodThatSucceedsWithoutEffectOnlyWarns() throws Exception {
        Path dir = tmp.resolve("state");
        Files.createDirectory(dir);
        Files.setPosixFilePermissions(dir, PosixFilePermissions.fromString("rwxr-xr-x"));
        StateDirectory sd = new StateDirectory(dir, null, null, (p, perms) -> { /* "succeeds", changes nothing */ });
        assertThat(sd.bearerToken()).hasSize(43);
        assertThat(sd.handleKey()).hasSize(32);
        assertThat(logs.list).anyMatch(e -> e.getLevel() == Level.WARN
                && e.getFormattedMessage().contains("still accessible by group or others"));
        assertThat(Files.exists(dir.resolve("http-token"))).isTrue();
    }

    // AC6

    @Test
    void unwritableDirectoryGivesEphemeralHandleKeyWithWarnButTokenFailsStartup() throws Exception {
        Path ro = tmp.resolve("ro");
        Files.createDirectory(ro);
        Files.setPosixFilePermissions(ro, Set.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_EXECUTE));
        try {
            assumeThat(Files.isWritable(ro)).as("running as root").isFalse();
            Path dir = ro.resolve("state");
            StateDirectory sd = new StateDirectory(dir);

            assertThat(sd.handleKey()).hasSize(32);
            assertThat(sd.handleKey()).as("stable within the process").isEqualTo(sd.handleKey());
            assertThat(logs.list).anyMatch(e -> e.getLevel() == Level.WARN
                    && e.getFormattedMessage().contains("handles will be invalid after restart"));
            assertThatThrownBy(sd::bearerToken).isInstanceOf(StateDirectoryException.class);
            assertThat(Files.exists(dir)).isFalse();
        } finally {
            Files.setPosixFilePermissions(ro, PosixFilePermissions.fromString("rwx------"));
        }
    }
}
