package de.alexanderlindhorst.imapmcp.it;

import com.icegreen.greenmail.util.GreenMail;
import com.icegreen.greenmail.util.ServerSetup;
import com.icegreen.greenmail.util.ServerSetupTest;
import jakarta.mail.MessagingException;

/** In-process IMAPS server on a dynamic port for fast IT cases that do not need Dovecot's protocol fidelity. */
public final class GreenMailFixture implements AutoCloseable {

    public static final String HOST = "127.0.0.1";

    private final GreenMail greenMail = new GreenMail(withStartupTimeout());

    /** GreenMail's 2000 ms default start timeout flakes under host load (#207). */
    private static ServerSetup withStartupTimeout() {
        ServerSetup setup = ServerSetupTest.IMAPS.dynamicPort();
        setup.setServerStartupTimeout(30_000L);
        return setup;
    }

    private GreenMailFixture() {
    }

    public static GreenMailFixture start() {
        GreenMailFixture f = new GreenMailFixture();
        f.greenMail.start();
        return f;
    }

    public void createUser(String email, String login, String password) {
        greenMail.setUser(email, login, password);
    }

    public int imapsPort() {
        return greenMail.getImaps().getPort();
    }

    public MailSeeder seeder(String login, String password) throws MessagingException {
        return new MailSeeder(HOST, imapsPort(), login, password);
    }

    @Override
    public void close() {
        greenMail.stop();
    }
}
