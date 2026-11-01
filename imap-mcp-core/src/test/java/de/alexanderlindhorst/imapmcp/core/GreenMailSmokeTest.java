package de.alexanderlindhorst.imapmcp.core;

import static org.assertj.core.api.Assertions.assertThat;

import com.icegreen.greenmail.junit5.GreenMailExtension;
import com.icegreen.greenmail.util.GreenMailUtil;
import com.icegreen.greenmail.util.ServerSetupTest;
import jakarta.mail.Flags;
import jakarta.mail.Folder;
import jakarta.mail.Message;
import jakarta.mail.Session;
import jakarta.mail.Store;
import java.util.Properties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

/** Proves GreenMail runs on JUnit 6 + the build JDK and is readable with Angus Mail over IMAPS. */
class GreenMailSmokeTest {

    @RegisterExtension
    static final GreenMailExtension GREEN_MAIL = new GreenMailExtension(ServerSetupTest.IMAPS.dynamicPort());

    @Test
    void readsDeliveredMessageOverImaps() throws Exception {
        var user = GREEN_MAIL.setUser("user@localhost", "user", "secret");
        user.deliver(GreenMailUtil.createTextEmail(
                "user@localhost", "sender@localhost", "smoke", "hello", GREEN_MAIL.getImaps().getServerSetup()));

        Properties props = new Properties();
        // imaps protocol => mail.imaps.* keys; test-only trust of GreenMail's self-signed cert
        props.put("mail.imaps.ssl.trust", "*");
        props.put("mail.imaps.ssl.checkserveridentity", "false");
        Session session = Session.getInstance(props);
        try (Store store = session.getStore("imaps")) {
            store.connect("127.0.0.1", GREEN_MAIL.getImaps().getPort(), "user", "secret");
            Folder inbox = store.getFolder("INBOX");
            inbox.open(Folder.READ_ONLY);
            try {
                assertThat(inbox.getMessageCount()).isEqualTo(1);
                Message m = inbox.getMessage(1);
                assertThat(m.getSubject()).isEqualTo("smoke");
                assertThat(m.getFlags().contains(Flags.Flag.SEEN)).isFalse();
            } finally {
                inbox.close(false);
            }
        }
    }
}
