package de.alexanderlindhorst.imapmcp.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;

/** Proves the test stack (JUnit 6, AssertJ, Mockito inline mocking) works on the build JDK. */
class BuildSmokeTest {

    static final class FinalCollaborator {
        String name() {
            return "real";
        }
    }

    @Test
    void mocksFinalClass() {
        FinalCollaborator mocked = mock(FinalCollaborator.class);
        when(mocked.name()).thenReturn("mock");
        assertThat(mocked.name()).isEqualTo("mock");
    }
}
