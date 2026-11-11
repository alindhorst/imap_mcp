package de.alexanderlindhorst.imapmcp.it;

import java.security.SecureRandom;
import java.util.HexFormat;
import java.util.List;

/**
 * The Dovecot of the deployed stack (#429, SPEC 5.8): the pinned 2.4 image of {@link ItImages#DOVECOT} with its
 * fixture files copied in at build time (never bind-mounted), implicit TLS on 993, a certificate for
 * {@link #HOST} signed at test time by the copy's local CA, and two users whose passwords exist only for this run.
 * The pure parts (the file contents) live here so that they can be unit tested; the build is in DeployedStack.
 */
final class DeployedDovecot {

    /** The host name the app is told to admit ({@code IMAP_HOSTS}) and the alias of the service on the backend network. */
    static final String HOST = "imap.imap-mcp.test";
    static final int PORT = 993;

    private static final SecureRandom RANDOM = new SecureRandom();

    private DeployedDovecot() {
    }

    /** A generated password; {@code toString} never shows it. */
    record Secret(String value) {
        @Override
        public String toString() {
            return "Secret[***]";
        }
    }

    /** A Dovecot user of this run; the password is exposed only through {@link Secret#value()}. */
    record ImapUser(String name, Secret password) {
    }

    static List<ImapUser> generateUsers() {
        return List.of(new ImapUser("alice-" + token(4), new Secret(token(24))),
                new ImapUser("bob-" + token(4), new Secret(token(24))));
    }

    private static String token(int bytes) {
        byte[] b = new byte[bytes];
        RANDOM.nextBytes(b);
        return HexFormat.of().formatHex(b);
    }

    static String passwdFile(List<ImapUser> users) {
        StringBuilder sb = new StringBuilder();
        for (ImapUser u : users) {
            sb.append(u.name()).append(":{PLAIN}").append(u.password().value()).append('\n');
        }
        return sb.toString();
    }

    /**
     * {@code conf.d/99-deployed.conf}: it comes after vendor.d, so port 993 replaces the image's rootless 31993
     * (the service gets {@code ip_unprivileged_port_start=0}); the image's static passdb has the locked default
     * password and falls through to this one.
     */
    static String config() {
        return """
                # test only (imap-mcp-it, deployed stack)
                ssl = required
                service imap-login {
                  inet_listener imaps {
                    port = 993
                    ssl = yes
                  }
                }
                passdb users {
                  driver = passwd-file
                  passwd_file_path = /etc/dovecot/users
                }
                """;
    }

    static String dockerfile() {
        return "FROM " + ItImages.DOVECOT + "\n"
                + "COPY tls.crt tls.key /etc/dovecot/ssl/\n"
                + "COPY users /etc/dovecot/users\n"
                + "COPY 99-deployed.conf /etc/dovecot/conf.d/99-deployed.conf\n"
                + "COPY seed/ /seed/\n";
    }

    static String leafExtension() {
        return "subjectAltName=DNS:" + HOST + "\nextendedKeyUsage=serverAuth\n";
    }

    static String newCanarySubject() {
        byte[] b = new byte[8];
        RANDOM.nextBytes(b);
        return "canary-" + HexFormat.of().formatHex(b);
    }

    static String seedMessage(String subject) {
        return "From: sender@example.test\r\n"
                + "To: recipient@example.test\r\n"
                + "Subject: " + subject + "\r\n"
                + "Date: Mon, 02 Nov 2026 10:00:00 +0000\r\n"
                + "Message-ID: <" + subject + "@example.test>\r\n"
                + "MIME-Version: 1.0\r\n"
                + "Content-Type: text/plain; charset=utf-8\r\n"
                + "\r\n"
                + "Seeded by the deployed-stack fixture.\r\n";
    }
}
