package de.alexanderlindhorst.imapmcp.core.imap;

import static org.assertj.core.api.Assertions.assertThat;

import com.icegreen.greenmail.junit5.GreenMailExtension;
import com.icegreen.greenmail.util.ServerSetupTest;
import java.util.Properties;
import java.util.Set;
import jakarta.mail.Session;
import org.eclipse.angus.mail.imap.IMAPStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

class ImapCapabilitiesTest {

    @RegisterExtension
    static final GreenMailExtension GREEN_MAIL = new GreenMailExtension(ServerSetupTest.IMAPS);

    @Test
    void mapsCapabilityNamesCaseInsensitively() {
        ImapCapabilities caps = ImapCapabilities.fromNames(Set.of("move", "UIDPLUS", "X-GM-EXT-1"));
        assertThat(caps.move()).isTrue();
        assertThat(caps.uidplus()).isTrue();
        assertThat(caps.gmail()).isTrue();
        assertThat(caps.sort()).isFalse();
        assertThat(caps.specialUse()).isFalse();
        assertThat(caps.raw()).containsExactlyInAnyOrder("MOVE", "UIDPLUS", "X-GM-EXT-1");
    }

    @Test
    void rawSetIsImmutable() {
        ImapCapabilities caps = ImapCapabilities.fromNames(Set.of("MOVE"));
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> caps.raw().add("X"))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void readsPostLoginCapabilitiesFromGreenMail() throws Exception {
        GREEN_MAIL.setUser("user@localhost", "user", "secret");
        Properties props = new Properties();
        // imaps protocol => mail.imaps.* keys; test-only trust of GreenMail's self-signed cert
        props.put("mail.imaps.ssl.trust", "*");
        props.put("mail.imaps.ssl.checkserveridentity", "false");
        try (IMAPStore store = (IMAPStore) Session.getInstance(props).getStore("imaps")) {
            store.connect("127.0.0.1", GREEN_MAIL.getImaps().getPort(), "user", "secret");
            ImapCapabilities caps = ImapCapabilities.from(store);
            assertThat(caps.uidplus()).isTrue();
            assertThat(caps.move()).isTrue();
            assertThat(caps.sort()).isTrue();
            assertThat(caps.specialUse()).isFalse();
            assertThat(caps.gmail()).isFalse();
        }
    }
}
