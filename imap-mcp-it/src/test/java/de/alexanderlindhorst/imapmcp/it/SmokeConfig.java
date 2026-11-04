package de.alexanderlindhorst.imapmcp.it;

import de.alexanderlindhorst.imapmcp.core.account.MailAccount;
import java.io.IOException;
import java.nio.file.Path;

/**
 * Builds the server config for one smoke account (#258) on top of {@link ServerConfigWriter}: the password is
 * written as an {@code env:} reference and handed to the child process through its environment only. Read checks use
 * a {@code readOnly} account so they cannot change anything even by mistake.
 */
final class SmokeConfig {

    static final String ACCOUNT_ID = "smoke";

    private SmokeConfig() {
    }

    static ServerConfig write(Path dir, SmokeEnv.Settings s, boolean writable) throws IOException {
        ServerConfigWriter w = new ServerConfigWriter(dir)
                .account(ACCOUNT_ID, s.host(), s.port(), MailAccount.Security.IMPLICIT_TLS, s.user(), s.password())
                // Real networks: generous timeouts; 60s is also above the 30s minimum of a writable account.
                .extraYaml("imap:\n  readTimeout: 60s\ntools:\n  deadline: 60s\n");
        if (!writable) {
            w.readOnly(ACCOUNT_ID);
        }
        if (s.trustStore() != null) {
            w.trustStore(s.trustStore());
        }
        return w.write();
    }
}
