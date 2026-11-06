package de.alexanderlindhorst.imapmcp.store.crypto;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import de.alexanderlindhorst.imapmcp.core.account.Secret;
import de.alexanderlindhorst.imapmcp.core.account.SecretRef;
import java.util.Arrays;
import java.util.Base64;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

class LocalKeyWrapperTest {

    private static byte[] key(int fill) {
        byte[] k = new byte[32];
        Arrays.fill(k, (byte) fill);
        return k;
    }

    @Test
    void wrapAndUnwrapRoundTripUnderTheSameContext() {
        LocalKeyWrapper w = new LocalKeyWrapper("local-1", Map.of("local-1", key(1)));
        byte[] dek = key(5);

        WrappedKey wrapped = w.wrap(dek, "tenant", "account");

        assertThat(wrapped.kekId()).isEqualTo("local-1");
        assertThat(wrapped.bytes()).hasSize(12 + 32 + 16);
        assertThat(new String(wrapped.bytes(), java.nio.charset.StandardCharsets.ISO_8859_1))
                .doesNotContain(new String(dek, java.nio.charset.StandardCharsets.ISO_8859_1));
        assertThat(w.unwrap("local-1", wrapped.bytes(), "tenant", "account")).isEqualTo(dek);
    }

    @Test
    void unwrapUnderAnotherTenantOrAccountFails() {
        LocalKeyWrapper w = new LocalKeyWrapper("local-1", Map.of("local-1", key(1)));
        WrappedKey wrapped = w.wrap(key(5), "tenant", "account");

        assertThatThrownBy(() -> w.unwrap("local-1", wrapped.bytes(), "other", "account"))
                .isInstanceOf(KeyWrapException.class);
        assertThatThrownBy(() -> w.unwrap("local-1", wrapped.bytes(), "tenant", "other"))
                .isInstanceOf(KeyWrapException.class);
    }

    @Test
    void anOlderKekStillUnwrapsAfterRotation() {
        LocalKeyWrapper before = new LocalKeyWrapper("local-1", Map.of("local-1", key(1)));
        WrappedKey wrapped = before.wrap(key(5), "t", "a");
        LocalKeyWrapper after = new LocalKeyWrapper("local-2", Map.of("local-1", key(1), "local-2", key(2)));

        assertThat(after.unwrap("local-1", wrapped.bytes(), "t", "a")).isEqualTo(key(5));
        assertThat(after.wrap(key(5), "t", "a").kekId()).isEqualTo("local-2");
    }

    @Test
    void currentKekMustBeInTheMapAndKeysMustBe32Bytes() {
        assertThatThrownBy(() -> new LocalKeyWrapper("missing", Map.of("local-1", key(1))))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new LocalKeyWrapper("local-1", Map.of("local-1", new byte[16])))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void fromReferenceReadsTheKekAndWarnsWithoutKeyMaterial() {
        Logger logger = (Logger) LoggerFactory.getLogger(LocalKeyWrapper.class);
        ListAppender<ILoggingEvent> logs = new ListAppender<>();
        logs.start();
        logger.addAppender(logs);
        Level old = logger.getLevel();
        logger.setLevel(Level.DEBUG);
        String b64 = Base64.getUrlEncoder().withoutPadding().encodeToString(key(3));
        try {
            LocalKeyWrapper w = LocalKeyWrapper.fromReference("local-1", new SecretRef("env:KEK"),
                    ref -> new Secret(b64.toCharArray()));

            assertThat(w.unwrap("local-1", w.wrap(key(5), "t", "a").bytes(), "t", "a")).isEqualTo(key(5));
            assertThat(logs.list).extracting(ILoggingEvent::getLevel).contains(Level.WARN);
            assertThat(logs.list).extracting(ILoggingEvent::getFormattedMessage)
                    .noneMatch(m -> m.contains(b64) || m.contains("env:KEK"));
        } finally {
            logger.detachAppender(logs);
            logger.setLevel(old);
        }
    }

    @Test
    void fromReferenceRejectsAMalformedKekWithoutEchoingIt() {
        assertThatThrownBy(() -> LocalKeyWrapper.fromReference("local-1", new SecretRef("env:KEK"),
                ref -> new Secret("not-a-key!!".toCharArray())))
                .isInstanceOf(IllegalArgumentException.class).hasMessageNotContaining("not-a-key");
    }
}
