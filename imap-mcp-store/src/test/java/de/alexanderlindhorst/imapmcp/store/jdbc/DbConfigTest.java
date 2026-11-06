package de.alexanderlindhorst.imapmcp.store.jdbc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class DbConfigTest {

    @Test
    void maxPoolSizeDefaultsToTen() {
        DbConfig config = DbConfig.of("jdbc:postgresql://db/imap", "imap", "pw".toCharArray());

        assertThat(config.maxPoolSize()).isEqualTo(10);
    }

    @Test
    void nonPostgresUrlIsRejected() {
        assertThatThrownBy(() -> DbConfig.of("jdbc:mysql://db/imap", "imap", "pw".toCharArray()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void poolSizeOutsideOneToOneHundredIsRejected() {
        assertThatThrownBy(() -> new DbConfig("jdbc:postgresql://db/imap", "imap", "pw".toCharArray(), 0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new DbConfig("jdbc:postgresql://db/imap", "imap", "pw".toCharArray(), 101))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void toStringNeverShowsThePassword() {
        DbConfig config = DbConfig.of("jdbc:postgresql://db/imap", "imap", "s3cret-pw".toCharArray());

        assertThat(config.toString()).doesNotContain("s3cret-pw");
    }
}
