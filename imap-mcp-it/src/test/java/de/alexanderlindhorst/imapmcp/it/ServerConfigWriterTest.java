package de.alexanderlindhorst.imapmcp.it;

import static org.assertj.core.api.Assertions.assertThat;

import de.alexanderlindhorst.imapmcp.app.config.AppConfig;
import de.alexanderlindhorst.imapmcp.app.config.ConfigLoader;
import de.alexanderlindhorst.imapmcp.core.account.MailAccount;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ServerConfigWriterTest {

    @TempDir
    Path dir;

    @Test
    void writesAConfigTheAppCanLoadWithPasswordAsEnvReference() throws Exception {
        ServerConfig cfg = new ServerConfigWriter(dir)
                .account("work", "localhost", 31993, MailAccount.Security.IMPLICIT_TLS, "alice", "s3cret")
                .account("home", "localhost", 31143, MailAccount.Security.STARTTLS, "bob", "other")
                .write();

        AppConfig loaded = ConfigLoader.load(cfg.configFile(), cfg.env());
        assertThat(loaded.accounts()).extracting(AppConfig.Account::id).containsExactly("work", "home");
        assertThat(loaded.accounts().get(1).security()).isEqualTo(MailAccount.Security.STARTTLS);
        assertThat(loaded.accounts().get(0).port()).isEqualTo(31993);
        // the secret is never in the file, only in the process environment
        assertThat(Files.readString(cfg.configFile())).doesNotContain("s3cret").contains("env:");
        assertThat(cfg.env().values()).contains("s3cret", "other");
    }

    @Test
    void createsStateAndLogDirsAndPointsTheEnvironmentAtThem() throws Exception {
        ServerConfig cfg = new ServerConfigWriter(dir)
                .account("work", "localhost", 31993, MailAccount.Security.IMPLICIT_TLS, "alice", "pw").write();

        assertThat(cfg.stateDir()).isDirectory();
        assertThat(cfg.logDir()).isDirectory();
        assertThat(cfg.env()).containsEntry("IMAP_MCP_STATE_DIR", cfg.stateDir().toString())
                .containsEntry("IMAP_MCP_LOG_DIR", cfg.logDir().toString());
    }

    @Test
    void trustIsATruststoreJvmArgumentNeverATrustAllSwitch() throws Exception {
        ServerConfig cfg = new ServerConfigWriter(dir)
                .account("work", "localhost", 31993, MailAccount.Security.IMPLICIT_TLS, "alice", "pw")
                .trustStore(TestTrust.writeTruststore(dir)).write();

        assertThat(cfg.jvmArgs()).anyMatch(a -> a.startsWith("-Djavax.net.ssl.trustStore=") && a.endsWith(".p12"))
                .contains("-Djavax.net.ssl.trustStorePassword=" + TestTrust.PASSWORD,
                        "-Djavax.net.ssl.trustStoreType=PKCS12");
        assertThat(Files.readString(cfg.configFile())).doesNotContain("trust");
    }

    @Test
    void extraYamlIsAppended() throws Exception {
        ServerConfig cfg = new ServerConfigWriter(dir)
                .account("work", "localhost", 31993, MailAccount.Security.IMPLICIT_TLS, "alice", "pw")
                .extraYaml("imap: {connectTimeout: 500ms}\n").write();

        assertThat(ConfigLoader.load(cfg.configFile(), cfg.env()).imap().connectTimeout())
                .isEqualTo(java.time.Duration.ofMillis(500));
    }

    @Test
    void writeToolsAreUsableByDefault() throws Exception {
        ServerConfig cfg = new ServerConfigWriter(dir)
                .account("work", "localhost", 31993, MailAccount.Security.IMPLICIT_TLS, "alice", "pw").write();

        AppConfig loaded = ConfigLoader.load(cfg.configFile(), cfg.env());
        assertThat(loaded.writeToolsUsable()).isTrue();
        assertThat(loaded.accounts()).extracting(AppConfig.Account::readOnly).containsExactly(false);
    }

    @Test
    void allAccountsReadOnlyDisablesTheWriteTools() throws Exception {
        ServerConfig cfg = new ServerConfigWriter(dir)
                .account("work", "localhost", 31993, MailAccount.Security.IMPLICIT_TLS, "alice", "pw")
                .account("home", "localhost", 31143, MailAccount.Security.STARTTLS, "bob", "pw")
                .readOnly("work").readOnly("home").write();

        AppConfig loaded = ConfigLoader.load(cfg.configFile(), cfg.env());
        assertThat(loaded.writeToolsUsable()).isFalse();
        assertThat(loaded.accounts()).extracting(AppConfig.Account::readOnly).containsExactly(true, true);
    }

    @Test
    void mixedReadOnlyKeepsTheWriteToolsAndEachAccountsFlag() throws Exception {
        ServerConfig cfg = new ServerConfigWriter(dir)
                .account("work", "localhost", 31993, MailAccount.Security.IMPLICIT_TLS, "alice", "pw")
                .account("home", "localhost", 31143, MailAccount.Security.STARTTLS, "bob", "pw")
                .readOnly("home").write();

        AppConfig loaded = ConfigLoader.load(cfg.configFile(), cfg.env());
        assertThat(loaded.writeToolsUsable()).isTrue();
        assertThat(loaded.accounts()).extracting(AppConfig.Account::id, AppConfig.Account::readOnly)
                .containsExactly(org.assertj.core.groups.Tuple.tuple("work", false),
                        org.assertj.core.groups.Tuple.tuple("home", true));
    }

    @Test
    void allowPurgeIsWrittenPerAccount() throws Exception {
        ServerConfig cfg = new ServerConfigWriter(dir)
                .account("work", "localhost", 31993, MailAccount.Security.IMPLICIT_TLS, "alice", "pw")
                .account("home", "localhost", 31143, MailAccount.Security.STARTTLS, "bob", "pw")
                .allowPurge("work").write();

        AppConfig loaded = ConfigLoader.load(cfg.configFile(), cfg.env());
        assertThat(loaded.accounts()).extracting(AppConfig.Account::id, AppConfig.Account::allowPurge)
                .containsExactly(org.assertj.core.groups.Tuple.tuple("work", true),
                        org.assertj.core.groups.Tuple.tuple("home", false));
    }

    @Test
    void readOnlyOfAnUnknownAccountIsRejected() {
        ServerConfigWriter w = new ServerConfigWriter(dir)
                .account("work", "localhost", 31993, MailAccount.Security.IMPLICIT_TLS, "alice", "pw");
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> w.readOnly("nope"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
