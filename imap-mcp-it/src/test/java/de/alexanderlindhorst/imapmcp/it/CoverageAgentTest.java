package de.alexanderlindhorst.imapmcp.it;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class CoverageAgentTest {

    @Test
    void rewritesDestfileToPerChildFileAndKeepsOtherOptions() {
        List<String> in = List.of("-Xmx1g",
                "-javaagent:/x/org.jacoco.agent-0.8.16-runtime.jar=destfile=/m/target/jacoco-it.exec,includes=de.alexanderlindhorst.imapmcp.*,append=true",
                "-javaagent:/y/mockito-core.jar");

        List<String> out = CoverageAgent.childArgs(in);

        assertThat(out).hasSize(1);
        String arg = out.get(0);
        assertThat(arg).startsWith("-javaagent:/x/org.jacoco.agent-0.8.16-runtime.jar=destfile=/m/target/jacoco-children/");
        String file = arg.substring(arg.indexOf("jacoco-children/") + "jacoco-children/".length(), arg.indexOf(",includes"));
        assertThat(file).endsWith(".exec");
        UUID.fromString(file.substring(0, file.length() - ".exec".length()));
        assertThat(arg).endsWith(",includes=de.alexanderlindhorst.imapmcp.*,append=true");
    }

    @Test
    void destfileLastOptionIsRewritten() {
        List<String> out = CoverageAgent.childArgs(List.of(
                "-javaagent:/x/org.jacoco.agent-0.8.16-runtime.jar=includes=a.*,destfile=/m/target/jacoco.exec"));
        assertThat(out).hasSize(1);
        assertThat(out.get(0)).contains("includes=a.*,destfile=/m/target/jacoco-children/").endsWith(".exec");
    }

    @Test
    void noJacocoAgentGivesEmptyList() {
        assertThat(CoverageAgent.childArgs(List.of("-javaagent:/y/mockito-core.jar", "-Xmx1g"))).isEmpty();
    }
}
