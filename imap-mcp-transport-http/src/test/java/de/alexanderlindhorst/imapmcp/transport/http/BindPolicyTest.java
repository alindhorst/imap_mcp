package de.alexanderlindhorst.imapmcp.transport.http;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class BindPolicyTest {

    @Test
    void loopbackIsAlwaysAllowed() {
        assertThat(BindPolicy.check("127.0.0.1", false).isLoopbackAddress()).isTrue();
        assertThat(BindPolicy.check("::1", false).isLoopbackAddress()).isTrue();
        assertThat(BindPolicy.check("localhost", false).isLoopbackAddress()).isTrue();
    }

    @Test
    void nonLoopbackRefusedWithoutFlagWithActionableMessage() {
        assertThatThrownBy(() -> BindPolicy.check("0.0.0.0", false)).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("http.allowNonLoopbackBind=true").hasMessageContaining("0.0.0.0");
        assertThatThrownBy(() -> BindPolicy.check("192.0.2.1", false)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void nonLoopbackAllowedWithFlag() {
        assertThat(BindPolicy.check("0.0.0.0", true).isLoopbackAddress()).isFalse();
    }

    @Test
    void unresolvableHostIsRefused() {
        assertThatThrownBy(() -> BindPolicy.check("no-such-host.invalid", true)).isInstanceOf(IllegalStateException.class);
    }
}
