package de.alexanderlindhorst.imapmcp.core.download;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class InlineLimiterTest {

    @Test
    void grantsAtMostMaxAndFreesOnRelease() {
        InlineLimiter l = new InlineLimiter(2);
        assertThat(l.tryAcquire()).isTrue();
        assertThat(l.tryAcquire()).isTrue();
        assertThat(l.tryAcquire()).isFalse();
        l.release();
        assertThat(l.tryAcquire()).isTrue();
        assertThat(l.tryAcquire()).isFalse();
    }

    @Test
    void aNonPositiveMaxIsRefused() {
        assertThatThrownBy(() -> new InlineLimiter(0)).isInstanceOf(IllegalArgumentException.class);
    }
}
