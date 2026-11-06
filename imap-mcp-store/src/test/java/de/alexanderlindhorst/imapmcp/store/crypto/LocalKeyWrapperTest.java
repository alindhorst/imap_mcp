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

    @Test
    void everyWrapUsesAFreshNonce() {
        LocalKeyWrapper w = new LocalKeyWrapper("local-1", Map.of("local-1", key(1)));

        byte[] a = Arrays.copyOf(w.wrap(key(5), "t", "a").bytes(), 12);
        byte[] b = Arrays.copyOf(w.wrap(key(5), "t", "a").bytes(), 12);

        assertThat(a).isNotEqualTo(b);
    }

    @Test
    void blankKekIdsAndTheSameKeyUnderTwoIdsAreRejected() {
        assertThatThrownBy(() -> new LocalKeyWrapper(" ", Map.of(" ", key(1))))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new LocalKeyWrapper("", Map.of("", key(1))))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new LocalKeyWrapper("a", Map.of("a", key(1), "b", key(1))))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void fromReferencesReadsEveryKekWithOneWarnAndRotationWorks() {
        Logger logger = (Logger) LoggerFactory.getLogger(LocalKeyWrapper.class);
        ListAppender<ILoggingEvent> logs = new ListAppender<>();
        logs.start();
        logger.addAppender(logs);
        Map<String, String> material = Map.of("env:K1", b64(key(1)), "env:K2", b64(key(2)));
        try {
            LocalKeyWrapper old = LocalKeyWrapper.fromReference("local-1", new SecretRef("env:K1"),
                    ref -> new Secret(material.get(ref.value()).toCharArray()));
            WrappedKey wrapped = old.wrap(key(5), "t", "a");
            logs.list.clear();

            LocalKeyWrapper rotated = LocalKeyWrapper.fromReferences("local-2",
                    new java.util.LinkedHashMap<>(Map.of("local-1", new SecretRef("env:K1"),
                            "local-2", new SecretRef("env:K2"))),
                    ref -> new Secret(material.get(ref.value()).toCharArray()));

            assertThat(rotated.unwrap("local-1", wrapped.bytes(), "t", "a")).isEqualTo(key(5));
            assertThat(rotated.wrap(key(5), "t", "a").kekId()).isEqualTo("local-2");
            assertThat(logs.list).filteredOn(e -> e.getLevel() == Level.WARN).hasSize(1);
        } finally {
            logger.detachAppender(logs);
        }
    }

    @Test
    void paddedOrWrongLengthBase64IsRejected() {
        String padded = Base64.getUrlEncoder().encodeToString(new byte[31]); // 31 bytes encode with '='
        assertThatThrownBy(() -> LocalKeyWrapper.fromReference("local-1", new SecretRef("env:KEK"),
                ref -> new Secret(padded.toCharArray()))).isInstanceOf(IllegalArgumentException.class);
        String paddedFull = b64(key(1)) + "=";
        assertThatThrownBy(() -> LocalKeyWrapper.fromReference("local-1", new SecretRef("env:KEK"),
                ref -> new Secret(paddedFull.toCharArray()))).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void aResolverFailureIsNotReportedAsABase64Problem() {
        assertThatThrownBy(() -> LocalKeyWrapper.fromReference("local-1", new SecretRef("env:KEK"), ref -> {
            throw new IllegalStateException("resolver down");
        })).isInstanceOf(IllegalStateException.class).hasMessage("resolver down");
    }

    @Test
    void theResolvedSecretIsWipedAfterReading() {
        char[][] seen = new char[1][];
        LocalKeyWrapper.fromReference("local-1", new SecretRef("env:KEK"), ref -> {
            Secret s = new Secret(b64(key(1)).toCharArray());
            seen[0] = s.chars();
            return s;
        });

        assertThat(seen[0]).containsOnly('\0');
    }

    private static String b64(byte[] b) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(b);
    }
}
